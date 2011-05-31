package de.fu_berlin.inf.dpp.stf.server.rmi.remotebot.widget;

import java.rmi.RemoteException;

public interface IRemoteBotToggleButton {

    /**********************************************
     * 
     * actions
     * 
     **********************************************/
    public void click() throws RemoteException;

    public void press() throws RemoteException;

    public void clickAndWait() throws RemoteException;

    public void setFocus() throws RemoteException;

    /**********************************************
     * 
     * states
     * 
     **********************************************/

    public boolean isPressed() throws RemoteException;

    public boolean isEnabled() throws RemoteException;

    public boolean isVisible() throws RemoteException;

    public boolean isActive() throws RemoteException;

    public String getText() throws RemoteException;

    public String getToolTipText() throws RemoteException;

    /**********************************************
     * 
     * waits until
     * 
     **********************************************/
    public void waitUntilIsEnabled() throws RemoteException;

}