package de.fu_berlin.inf.dpp.net.internal;

import java.util.Iterator;

import org.apache.log4j.Logger;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smackx.Form;
import org.jivesoftware.smackx.FormField;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.picocontainer.annotations.Inject;

import de.fu_berlin.inf.dpp.Saros;
import de.fu_berlin.inf.dpp.annotations.Component;
import de.fu_berlin.inf.dpp.observables.SessionIDObservable;

@Component(module = "net")
public class MultiUserChatManager {

    private static Logger log = Logger.getLogger(MultiUserChatManager.class
        .getName());

    // TODO Room name should be configured by settings.
    /* name of multi user chat room */
    private String room = "saros";

    /* host name of jabber-server on which the muc room is created */
    private final String server = "conference.jabber.org";

    /* current muc connection. */
    private MultiUserChat muc;

    @Inject
    protected Saros saros;

    @Inject
    protected SessionIDObservable sessionID;

    public void initMUC(XMPPConnection connection, String user, String room)
        throws XMPPException {
        this.room = room;
        initMUC(connection, user);
    }

    public void initMUC(XMPPConnection connection, String user)
        throws XMPPException {

        /* create room domain of current connection. */
        // JID(connection.getUser()).getDomain();
        String host = this.room + "@" + this.server;

        // Create a MultiUserChat using an XMPPConnection for a room
        MultiUserChat muc = new MultiUserChat(connection, host);

        // try to join to room
        try {
            muc.join(user);
        } catch (XMPPException e) {
            MultiUserChatManager.log.debug(e);
            if (e.getMessage().contains("404")) {
                // room doesn't exist

                try {

                    // Create the room
                    muc.create("testbot");

                    // Get the the room's configuration form
                    Form form = muc.getConfigurationForm();

                    // Create a new form to submit based on the original form
                    Form submitForm = form.createAnswerForm();

                    // Add default answers to the form to submit
                    for (Iterator<FormField> fields = form.getFields(); fields
                        .hasNext();) {
                        FormField field = fields.next();
                        if (!FormField.TYPE_HIDDEN.equals(field.getType())
                            && (field.getVariable() != null)) {
                            // Sets the default value as the answer
                            submitForm.setDefaultAnswer(field.getVariable());
                        }
                    }

                    // set configuration, see XMPP Specs
                    submitForm.setAnswer("muc#roomconfig_moderatedroom", true);
                    submitForm.setAnswer("muc#roomconfig_allowinvites", true);
                    submitForm
                        .setAnswer("muc#roomconfig_persistentroom", false);

                    // Send the completed form (with default values) to the
                    // server to configure the room
                    muc.sendConfigurationForm(submitForm);

                } catch (XMPPException ee) {
                    MultiUserChatManager.log.debug(e.getLocalizedMessage(), ee);
                    throw ee;
                }
            } else {
                MultiUserChatManager.log.debug(e.getLocalizedMessage(), e);
                throw e;
            }
        }
        this.muc = muc;
    }

    /**
     * this method returns current muc or null no muc exists.
     * 
     * @return
     */
    public MultiUserChat getMUC() {
        return this.muc;
    }

    public String getRoomName() {
        return this.room;
    }

    public boolean isConnected() {
        if ((this.muc != null) && this.muc.isJoined()) {
            return true;
        }
        return false;
    }
}
