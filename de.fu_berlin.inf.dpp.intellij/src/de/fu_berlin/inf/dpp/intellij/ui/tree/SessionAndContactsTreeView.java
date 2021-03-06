package de.fu_berlin.inf.dpp.intellij.ui.tree;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.UIUtil;
import de.fu_berlin.inf.dpp.SarosPluginContext;
import de.fu_berlin.inf.dpp.account.XMPPAccountStore;
import de.fu_berlin.inf.dpp.intellij.ui.util.IconManager;
import de.fu_berlin.inf.dpp.net.ConnectionState;
import de.fu_berlin.inf.dpp.net.xmpp.IConnectionListener;
import de.fu_berlin.inf.dpp.net.xmpp.JID;
import de.fu_berlin.inf.dpp.net.xmpp.XMPPConnectionService;
import org.jivesoftware.smack.Connection;
import org.jivesoftware.smack.Roster;
import org.picocontainer.annotations.Inject;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.Component;

/**
 * Saros tree view for contacts and sessions.
 */
public class SessionAndContactsTreeView extends JTree {

    private final SessionTreeRootNode sessionTreeRootNode;
    private final ContactTreeRootNode contactTreeRootNode;

    @Inject
    private XMPPAccountStore accountStore;

    @Inject
    private XMPPConnectionService connectionService;

    /**
     * A cell renderer that sets the node icon according to the node type
     * (root, session, contact, or other).
     */
    private final TreeCellRenderer renderer = new DefaultTreeCellRenderer() {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
            boolean selected, boolean expanded, boolean isLeaf, int row,
            boolean focused) {
            Component c = super
                .getTreeCellRendererComponent(tree, value, selected, expanded,
                    isLeaf, row, focused);

            TreePath path = tree.getPathForRow(row);
            if (path != null) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path
                    .getLastPathComponent();

                if (node != null) {
                    if (node instanceof SarosTreeRootNode) {
                        setIcon(null);
                    } else if (node instanceof SessionTreeRootNode) {
                        setIcon(IconManager.SESSIONS_ICON);
                    } else if (node instanceof ContactTreeRootNode) {
                        setIcon(IconManager.CONTACTS_ICON);
                    } else {
                        if (node.getUserObject() instanceof LeafInfo) {
                            LeafInfo info = (LeafInfo) node.getUserObject();
                            if (info.getIcon() != null) {
                                setIcon(info.getIcon());
                            }
                        }
                    }
                }
            }

            return c;
        }

    };

    private final IConnectionListener connectionStateListener = new IConnectionListener() {

        @Override
        public void connectionStateChanged(Connection connection,
            ConnectionState state) {
            renderConnectionState(connection, state);
        }

    };

    public SessionAndContactsTreeView() {
        super(new SarosTreeRootNode());
        SarosPluginContext.initComponent(this);

        sessionTreeRootNode = new SessionTreeRootNode(this);
        ((SarosTreeRootNode) getModel().getRoot()).add(sessionTreeRootNode);
        contactTreeRootNode = new ContactTreeRootNode(this);
        ((SarosTreeRootNode) getModel().getRoot()).add(contactTreeRootNode);

        addMouseListener(new TreeClickListener(this));

        getSelectionModel()
            .setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setCellRenderer(renderer);

        connectionService.addListener(connectionStateListener);

        //show correct initial state
        renderConnectionState(connectionService.getConnection(), connectionService.getConnectionState());
    }

    private void renderConnectionState(Connection connection, ConnectionState state) {

        switch (state) {
        case CONNECTED:
            renderConnected();
            break;
        case ERROR:
        case NOT_CONNECTED:
            renderDisconnected();
            break;
        default:
            return;
        }
    }

    /**
     * Displays the 'user@domain (connected)' string and populates the contact list.
     * <p/>
     * Called after a connection was made.
     */
    private void renderConnected() {
        UIUtil.invokeLaterIfNeeded(new Runnable() {
            @Override
            public void run() {
                Connection connection = connectionService.getConnection();
                if (connection == null)
                    return;

                String userJID = connection.getUser();
                String rootText = new JID(userJID).getBareJID().toString();

                getSarosTreeRootNode().setTitle(rootText);

                updateTree();
            }
        });

    }

    /**
     * Clears the contact list and title.
     * <p/>
     * Called after a connection was disconnected.
     */
    private void renderDisconnected() {
        UIUtil.invokeLaterIfNeeded(new Runnable() {
            @Override
            public void run() {
                getSarosTreeRootNode().setTitleDefault();

                contactTreeRootNode.removeContacts();
                sessionTreeRootNode.removeAllChildren();

                updateTree();
            }
        });

    }

    private void updateTree() {
        DefaultTreeModel model = (DefaultTreeModel) (getModel());
        model.reload();

        expandRow(2);
    }

    protected ContactTreeRootNode getContactTreeRootNode() {
        return contactTreeRootNode;
    }

    private SarosTreeRootNode getSarosTreeRootNode() {
        return (SarosTreeRootNode) getModel().getRoot();
    }
}
