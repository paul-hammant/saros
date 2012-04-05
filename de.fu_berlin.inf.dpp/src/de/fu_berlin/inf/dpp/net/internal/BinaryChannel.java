package de.fu_berlin.inf.dpp.net.internal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.time.StopWatch;
import org.apache.log4j.Logger;
import org.eclipse.core.runtime.IProgressMonitor;
import org.jivesoftware.smackx.bytestreams.BytestreamSession;

import de.fu_berlin.inf.dpp.exceptions.LocalCancellationException;
import de.fu_berlin.inf.dpp.exceptions.RemoteCancellationException;
import de.fu_berlin.inf.dpp.exceptions.SarosCancellationException;
import de.fu_berlin.inf.dpp.net.IncomingTransferObject;
import de.fu_berlin.inf.dpp.net.NetTransferMode;
import de.fu_berlin.inf.dpp.util.AutoHashMap;
import de.fu_berlin.inf.dpp.util.Utils;

/**
 * BinaryChannel is a class that encapsulates a bidirectional communication
 * channel between two participants.
 * 
 * The threading requirements of this class are the following:
 * 
 * send() is a reentrant method for sending data. Any number of threads can call
 * it in parallel.
 * 
 * 
 * 
 * @author sszuecs
 * @author coezbek
 * @author Stefan Rossbach
 */
public class BinaryChannel {

    private static class Opcode {
        /* these opcodes will be cropped to byte values, do not exceed 0xFF ! */

        // private static final int CIPHER_REQUEST = 0xA0;
        // private static final int CIPHER_RESPONSE = 0xA1;

        @Deprecated
        private static final int TRANSFERDESCRIPTION = 0xFA;
        @Deprecated
        private static final int DATA = 0xFB;
        @Deprecated
        private static final int CANCEL = 0xFC;
        @Deprecated
        private static final int FINISHED = 0xFD;
        @Deprecated
        private static final int REJECT = 0xFE;
    }

    /**
     * Known error codes that do not need any special debug or error output
     */
    private static final String[] ACCEPTED_ERROR_CODES_ON_CLOSURE = {
        "service-unavailable(503)" /* peer closed stream already (SOCKS5) */,
        "recipient-unavailable(404)" /* peer is offline (IBB) */};

    private static final Logger log = Logger.getLogger(BinaryChannel.class);

    /**
     * Max size of data chunks
     */
    private static final int CHUNKSIZE = 32 * 1024 - 1;

    private AtomicInteger nextFragmentId = new AtomicInteger(0);

    // private boolean enableEncryption;

    private boolean connected;

    /**
     * Collect the Packets until an entire Object is received. objectid -->
     * [Packet0, Packet1, ..]
     */

    private Map<Integer, BlockingQueue<byte[]>> incomingPackets = AutoHashMap
        .getBlockingQueueHashMap();
    {
        incomingPackets = Collections.synchronizedMap(incomingPackets);
    }

    private ConcurrentHashMap<Integer, Integer> transmissionStatus = new ConcurrentHashMap<Integer, Integer>();
    private ConcurrentHashMap<Integer, CountDownLatch> transmissionReply = new ConcurrentHashMap<Integer, CountDownLatch>();

    private DataInputStream inputStream;
    private DataOutputStream outputStream;
    private BytestreamSession session;

    /**
     * NetTransferMode to identify the transport method of the underlying socket
     * connection.
     */
    private NetTransferMode transferMode;

    public BinaryChannel(BytestreamSession session, NetTransferMode mode)
        throws IOException {
        this.session = session;
        this.session.setReadTimeout(0); // keep connection alive
        this.transferMode = mode;

        /*
         * enableEncryption = Boolean.valueOf(System.getProperty(
         * "de.fu_berlin.inf.dpp.net.connection.ENCRYPT", "false"));
         */

        outputStream = new DataOutputStream(new BufferedOutputStream(
            session.getOutputStream()));
        inputStream = new DataInputStream(new BufferedInputStream(
            session.getInputStream()));

        connected = true;
    }

    /**
     * Reads the next incoming transfer object. The payload of this object may
     * not completely received at this point !
     * 
     * @return returns the next incoming transfer object
     * 
     * @throws IOException
     *             If the associated socket broke, while reading or if the
     *             socket has already been disposed.
     * @throws ClassNotFoundException
     *             If the data sent from the other side could not be decoded.
     */
    IncomingTransferObject receiveIncomingTransferObject() throws IOException,
        ClassNotFoundException {

        while (!Thread.currentThread().isInterrupted()) {

            int opcode = inputStream.read();

            if (opcode == -1)
                throw new EOFException("no stream data available");

            int fragmentId;

            if (log.isTraceEnabled())
                log.trace("processing opcode: "
                    + Integer.toHexString(opcode).toUpperCase());

            CountDownLatch latch;

            int payloadLength;

            switch (opcode) {
            case Opcode.TRANSFERDESCRIPTION:
                fragmentId = inputStream.readShort();
                int chunks = inputStream.readInt();
                payloadLength = inputStream.readInt();

                if (payloadLength <= 0 || payloadLength > CHUNKSIZE)
                    throw new ProtocolException(
                        "payload length field contains corrupted value: 0 < "
                            + payloadLength + " <= " + CHUNKSIZE);

                byte[] transferDescriptionData = new byte[payloadLength];
                inputStream.readFully(transferDescriptionData);

                TransferDescription transferDescription = TransferDescription
                    .fromByteArray(transferDescriptionData);

                /* Side effects are cool, aren't they ?????? */

                // Side-effect! Create a new BlockingQueue in the
                // incomingPackets AutoHashMap!
                BlockingQueue<byte[]> queue = incomingPackets.get(fragmentId);
                queue.clear();
                return new BinaryChannelTransferObject(this,
                    transferDescription, fragmentId, chunks, queue);

            case Opcode.DATA:
                fragmentId = inputStream.readShort();
                payloadLength = inputStream.readInt();

                if (payloadLength <= 0 || payloadLength > CHUNKSIZE)
                    throw new ProtocolException(
                        "payload length field contains corrupted value: 0 < "
                            + payloadLength + " <= " + CHUNKSIZE);

                byte[] payload = new byte[payloadLength];
                inputStream.readFully(payload);
                incomingPackets.get(fragmentId).add(payload);
                break;
            case Opcode.CANCEL:
                fragmentId = inputStream.readShort();
                transmissionStatus.put(fragmentId, Opcode.CANCEL);
                latch = transmissionReply.get(fragmentId);
                if (latch != null)
                    latch.countDown();
                break;
            case Opcode.FINISHED:
                fragmentId = inputStream.readShort();
                transmissionStatus.put(fragmentId, Opcode.FINISHED);
                latch = transmissionReply.get(fragmentId);
                if (latch != null)
                    latch.countDown();
                break;
            case Opcode.REJECT:
                fragmentId = inputStream.readShort();
                transmissionStatus.put(fragmentId, Opcode.REJECT);
                latch = transmissionReply.get(fragmentId);
                if (latch != null)
                    latch.countDown();
                break;
            default:
                close();
                throw new ProtocolException("unknown opcode: 0x"
                    + Integer.toHexString(opcode).toUpperCase());
            }
        }

        // clear the interrupt flag
        Thread.interrupted();
        throw new InterruptedIOException(
            "interrupted while reading stream data");
    }

    public synchronized boolean isConnected() {
        return connected;
    }

    public NetTransferMode getTransferMode() {
        return transferMode;
    }

    /**
     * Shutdown the entire connection represented by this BinaryChannel. It
     * closes the Socket, the ObjectInputStream and the ObjectOutputStream.
     */
    public synchronized void close() {

        if (!connected)
            return;

        try {
            session.close();
        } catch (IOException e) {
            if (!isAcceptedOnClosure(e))
                log.debug("Close failed cause: " + e.getMessage(), e);
        } finally {
            connected = false;
        }
    }

    /**
     * It sends the given transferDescription and data direct. Supports
     * cancellation by given SubMonitor.
     * 
     * 
     * @blocking
     * 
     * @throws IOException
     *             If there was an error sending (for instance the socket is
     *             closed) or
     * 
     * @throws SarosCancellationException
     *             if either the local or remote user aborted the transfer
     */
    public void send(TransferDescription transferDescription, byte[] data,
        IProgressMonitor monitor) throws IOException,
        SarosCancellationException {

        if (!isConnected())
            throw new IOException("connection is closed");

        int fragmentId = nextFragmentId.getAndIncrement() & 0x7FFF;

        transmissionStatus.put(fragmentId, /* unknown */-1);
        transmissionReply.put(fragmentId, new CountDownLatch(1));

        byte[] descData = TransferDescription.toByteArray(transferDescription);

        assert data.length > 0;

        try {
            int chunks = ((data.length - 1) / CHUNKSIZE) + 1;

            sendTransferDescription(descData, fragmentId, chunks);

            splitAndSend(data, chunks, fragmentId, monitor);

            int confirmation = -1;
            boolean transmitted = false;

            /* omg */

            try {
                for (int i = 0; i < 10; i++) {
                    transmitted = transmissionReply.get(fragmentId).await(1000,
                        TimeUnit.MILLISECONDS);
                }

            } catch (InterruptedException e) {
                Thread.interrupted();
                throw new InterruptedIOException(
                    "interrupted while waiting for transmission reply");
            }

            if (!transmitted)
                throw new IOException("transmission reply timed out");

            confirmation = transmissionStatus.get(fragmentId);

            if (confirmation == Opcode.REJECT)
                throw new RemoteCancellationException();

            assert confirmation == Opcode.FINISHED;

        } catch (LocalCancellationException e) {

            log.debug("send was canceled:" + fragmentId);

            sendCancel(fragmentId);
            throw e;
        } finally {
            transmissionStatus.remove(fragmentId);
            transmissionReply.remove(fragmentId);
        }
    }

    synchronized void sendData(int fragmentId, byte[] data, int offset,
        int length) throws IOException {
        outputStream.write(Opcode.DATA);
        outputStream.writeShort(fragmentId);
        outputStream.writeInt(length);
        outputStream.write(data, offset, length);
        outputStream.flush();
    }

    synchronized void sendTransferDescription(byte[] description,
        int fragmentId, int chunks) throws IOException {
        outputStream.write(Opcode.TRANSFERDESCRIPTION);
        outputStream.writeShort(fragmentId);
        outputStream.writeInt(chunks);
        outputStream.writeInt(description.length);
        outputStream.write(description);
        outputStream.flush();
    }

    synchronized void sendFinished(int fragmentId) throws IOException {
        outputStream.write(Opcode.FINISHED);
        outputStream.writeShort(fragmentId);
        outputStream.flush();
    }

    synchronized void sendCancel(int fragmentId) throws IOException {
        outputStream.write(Opcode.CANCEL);
        outputStream.writeShort(fragmentId);
        outputStream.flush();
    }

    synchronized void sendReject(int fragmentId) throws IOException {
        outputStream.write(Opcode.REJECT);
        outputStream.writeShort(fragmentId);
        outputStream.flush();
    }

    void removeFragments(int fragmentId) {
        incomingPackets.get(fragmentId).clear();
        transmissionStatus.remove(fragmentId);
        transmissionReply.remove(fragmentId);
    }

    boolean isRejected(int fragmentId) {
        Integer opcode = transmissionStatus.get(fragmentId);
        if (opcode != null) {
            return opcode == Opcode.REJECT;
        }
        return false;
    }

    boolean isCanceled(int fragmentId) {
        Integer opcode = transmissionStatus.get(fragmentId);
        if (opcode != null) {
            return opcode == Opcode.CANCEL;
        }
        return false;
    }

    /**
     * Splits the given data into chunks of CHUNKSIZE to send the BinaryPackets.
     */
    private void splitAndSend(byte[] data, int chunks, int fragmentId,
        IProgressMonitor monitor) throws SarosCancellationException,
        IOException {

        int offset = 0;
        int length = 0;

        StopWatch watch = new StopWatch();

        monitor.beginTask("", chunks);

        while (chunks-- > 0) {

            if (isRejected(fragmentId))
                throw new RemoteCancellationException();

            if (monitor.isCanceled())
                throw new LocalCancellationException();

            length = Math.min(data.length - offset, CHUNKSIZE);

            watch.start();

            sendData(fragmentId, data, offset, length);

            offset += length;

            long duration = watch.getTime();

            watch.reset();

            long bytesPerSecond = Math
                .round((length * 1000D) / (duration + 1D));

            long secondsLeft = Math.round((data.length - offset)
                / (bytesPerSecond + 1D));

            monitor.subTask("Remaining time: "
                + Utils.formatDuration(secondsLeft) + " ("
                + Utils.formatByte(bytesPerSecond) + "/s)");

            monitor.worked(1);

        }
        monitor.subTask("");
    }

    /**
     * See {{@link #ACCEPTED_ERROR_CODES_ON_CLOSURE}
     * 
     * @param e
     * @return whether the error should be logged
     */
    private boolean isAcceptedOnClosure(IOException e) {
        for (String s : ACCEPTED_ERROR_CODES_ON_CLOSURE) {
            if (e.getMessage().contains(s))
                return true;
        }
        return false;
    }
}