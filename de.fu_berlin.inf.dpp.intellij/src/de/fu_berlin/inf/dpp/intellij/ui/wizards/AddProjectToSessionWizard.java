package de.fu_berlin.inf.dpp.intellij.ui.wizards;

import java.awt.Window;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.picocontainer.annotations.Inject;

import com.intellij.ide.highlighter.ModuleFileType;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.UIUtil;

import de.fu_berlin.inf.dpp.filesystem.IChecksumCache;
import de.fu_berlin.inf.dpp.filesystem.IProject;
import de.fu_berlin.inf.dpp.filesystem.IWorkspace;
import de.fu_berlin.inf.dpp.intellij.filesystem.Filesystem;
import de.fu_berlin.inf.dpp.intellij.filesystem.IntelliJProjectImplV2;
import de.fu_berlin.inf.dpp.intellij.ui.Messages;
import de.fu_berlin.inf.dpp.intellij.ui.util.DialogUtils;
import de.fu_berlin.inf.dpp.intellij.ui.util.NotificationPanel;
import de.fu_berlin.inf.dpp.intellij.ui.util.SafeDialogUtils;
import de.fu_berlin.inf.dpp.intellij.ui.widgets.progress.ProgessMonitorAdapter;
import de.fu_berlin.inf.dpp.intellij.ui.wizards.pages.HeaderPanel;
import de.fu_berlin.inf.dpp.intellij.ui.wizards.pages.PageActionListener;
import de.fu_berlin.inf.dpp.intellij.ui.wizards.pages.SelectProjectPage;
import de.fu_berlin.inf.dpp.intellij.ui.wizards.pages.TextAreaPage;
import de.fu_berlin.inf.dpp.monitoring.IProgressMonitor;
import de.fu_berlin.inf.dpp.monitoring.NullProgressMonitor;
import de.fu_berlin.inf.dpp.monitoring.SubProgressMonitor;
import de.fu_berlin.inf.dpp.negotiation.CancelListener;
import de.fu_berlin.inf.dpp.negotiation.FileList;
import de.fu_berlin.inf.dpp.negotiation.FileListDiff;
import de.fu_berlin.inf.dpp.negotiation.FileListFactory;
import de.fu_berlin.inf.dpp.negotiation.IncomingProjectNegotiation;
import de.fu_berlin.inf.dpp.negotiation.NegotiationTools;
import de.fu_berlin.inf.dpp.negotiation.ProjectNegotiation;
import de.fu_berlin.inf.dpp.negotiation.ProjectNegotiationData;
import de.fu_berlin.inf.dpp.net.xmpp.JID;
import de.fu_berlin.inf.dpp.session.ISarosSession;
import de.fu_berlin.inf.dpp.session.ISarosSessionManager;
import de.fu_berlin.inf.dpp.util.ThreadUtils;

/**
 * Wizard for adding projects to a session.
 * <p/>
 * It consists of a selection page, where the user can select how to handle the
 * incoming project:
 * <ul>
 * <li> create a new project
 * <li reuse an existing project
 * </ul>
 * <p/>
 * If the option to reuse an existing project is chosen, a second page is displayed,
 * that displays the files necessary to modify.
 * <p/>
 *
 */

//  FIXME: Add facility for more than one project.

public class AddProjectToSessionWizard extends Wizard {
    private static final Logger LOG = Logger
        .getLogger(AddProjectToSessionWizard.class);

    public static final String SELECT_PROJECT_PAGE_ID = "selectProject";
    public static final String FILE_LIST_PAGE_ID = "fileListPage";

    private final String remoteProjectID;
    private final String remoteProjectName;

    private final IncomingProjectNegotiation negotiation;
    private final JID peer;

    private boolean triggered = false;

    /**
     * projectID => Project
     */
    private final Map<String, IProject> localProjects;

    @Inject
    private IChecksumCache checksumCache;

    @Inject
    private ISarosSessionManager sessionManager;

    @Inject
    private IWorkspace workspace;

    private final SelectProjectPage selectProjectPage;
    private final TextAreaPage fileListPage;

    private final PageActionListener selectProjectsPageListener = new PageActionListener() {
        @Override
        public void back() {
            // Nothing to do
        }

        /**
         * Extracts the local project's name (currently it has to be the same
         * as the original project) and either displays the filesChangedPage or
         * triggers the project negotiation
         */
        @Override
        public void next() {
            //FIXME: Only projects with the same name are supported,
            //because the project name is connected to the name of the .iml file
            //and it is unclear how that resolves.
            final String moduleName = selectProjectPage.getLocalProjectName();

            if (selectProjectPage.isNewProjectSelected()) {
                Module module;

                try {
                    module = createModuleStub(moduleName);

                } catch (IOException e) {
                    LOG.error("Could not create the shared module " + moduleName
                        + ".", e);

                    cancelNegotiation("Failed to create shared module");

                    SafeDialogUtils.showError("The module " + moduleName +
                        " could not be created. The project negotiation " +
                        "was aborted.\n" +
                        "To get help with this problem, please contact the " +
                        "Saros development team. You can reach us by writing " +
                        "to our mailing list " +
                        "(saros-devel@googlegroups.com) or by using our " +
                        "contact form " +
                        "(https://www.saros-project.org/contact/Website%20feedback).",
                        "Negotiation aborted!");

                    return;
                }

                IProject sharedProject = new IntelliJProjectImplV2(module);

                localProjects.put(remoteProjectID, sharedProject);

                triggerProjectNegotiation();

            } else {
                IProject sharedProject = workspace.getProject(moduleName);

                if (sharedProject == null) {
                    LOG.error("Could not find the shared module " + moduleName +
                        ".");

                    cancelNegotiation("Could not find chosen local " +
                        "representation of shared module");

                    SafeDialogUtils.showError("The chosen module " +
                        moduleName + " could not be found. The project " +
                        "negotiation was aborted.\n" +
                        "Please make sure that the module is correctly " +
                        "configured in the current project and exists on " +
                        "disk.\n" +
                        "If there seems to be no problem with the module, " +
                        "please contact the Saros development team. You can " +
                        "reach us by writing to our mailing list " +
                        "(saros-devel@googlegroups.com) or by using our " +
                        "contact form " +
                        "(https://www.saros-project.org/contact/Website%20feedback).",
                        "Negotiation aborted!");

                    return;
                }

                localProjects.put(remoteProjectID, sharedProject);

                prepareFilesChangedPage(localProjects);
            }
        }

        @Override
        public void cancel() {
            cancelNegotiation("Not accepted");
        }
    };

    /**
     * Cancels the project negotiation, notifies the host using the given
     * reason, and closes the wizard.
     * <p>
     * <b>Note:</b> This is an asynchronous action. It is not guaranteed
     * that the negotiation is canceled when this method returns.
     * </p>
     *
     * @param reason description why the negotiation was canceled
     */
    private void cancelNegotiation(@NotNull final String reason) {
        ThreadUtils.runSafeAsync(LOG, new Runnable() {

            @Override
            public void run() {
                negotiation.localCancel(reason,
                    NegotiationTools.CancelOption.NOTIFY_PEER);
            }

        });

        close();
    }

    /**
     * Creates an empty stub module with the given name in the base directory of
     * the current project.
     * <p>
     * The created module has the module type
     * {@link IntelliJProjectImplV2#RELOAD_STUB_MODULE_TYPE} which allows us to
     * easily identify it as stub.
     *
     * @param moduleName name of the module
     *
     * @return a stub <code>Module</code> object with the given name
     *
     *
     * @throws FileNotFoundException if the base directory of the current
     *                               project, the base directory of the created
     *                               module, or the module file of the created
     *                               module could not be found in the local
     *                               filesystem
     * @throws IOException if the creation of the module did
     *                     not return a valid <code>Module</code> object
     */
    @NotNull
    private Module createModuleStub(@NotNull final String moduleName)
        throws IOException {

        Module module = Filesystem
            .runWriteAction(new ThrowableComputable<Module, IOException>() {

                @Override
                public Module compute() throws IOException {
                    VirtualFile baseDir = project.getBaseDir();

                    if (baseDir == null) {
                        throw new FileNotFoundException("Could not find base" +
                            " directory for project " + project + ".");
                    }

                    Path moduleBasePath = Paths.get(baseDir.getPath())
                        .resolve(moduleName);

                    Path moduleFilePath = moduleBasePath.resolve(
                        moduleName + ModuleFileType.DOT_DEFAULT_EXTENSION);

                    ModifiableModuleModel modifiableModuleModel = ModuleManager
                        .getInstance(project).getModifiableModel();

                    Module module = modifiableModuleModel
                        .newModule(moduleFilePath.toString(),
                            IntelliJProjectImplV2.RELOAD_STUB_MODULE_TYPE);

                    modifiableModuleModel.commit();
                    project.save();

                    ModifiableRootModel modifiableRootModel = ModuleRootManager
                        .getInstance(module).getModifiableModel();

                    VirtualFile moduleFile = module.getModuleFile();

                    if (moduleFile == null) {
                        throw new FileNotFoundException("Could not find " +
                            "module file for module " + module + " after " +
                            "creating it.");
                    }

                    VirtualFile moduleRoot = moduleFile.getParent();

                    if (moduleRoot == null) {
                        throw new FileNotFoundException("Could not  find base" +
                            " directory for module " + module + ".");
                    }

                    modifiableRootModel.addContentEntry(moduleRoot);

                    modifiableRootModel.commit();

                    return module;
                }
            }, ModalityState.any());

        if (module == null) {
            throw new IOException("The creation of the module " + moduleName
                + " did not return a valid reference to the new module.");
        }

        return module;
    }

    private final PageActionListener fileListPageListener = new PageActionListener() {
        @Override
        public void back() {
            // Nothing to do
        }

        @Override
        public void next() {
            triggerProjectNegotiation();
        }

        @Override
        public void cancel() {
            cancelNegotiation("Not accepted");
        }
    };

    private final CancelListener cancelListener = new CancelListener() {

        @Override
        public void canceled(final NegotiationTools.CancelLocation location,
            final String message) {
            cancelWizard(peer, message, location);
        }
    };

    /**
     * Creates the wizard and its pages.
     *
     * @param negotiation  The IPN this wizard handles

     */
    public AddProjectToSessionWizard(Window parent,
        IncomingProjectNegotiation negotiation) {

        super(parent, Messages.AddProjectToSessionWizard_title,
            new HeaderPanel(Messages.EnterProjectNamePage_title2, ""));

        this.negotiation = negotiation;
        this.peer = negotiation.getPeer();


        List<ProjectNegotiationData> data = negotiation.getProjectNegotiationData();

        localProjects = new HashMap<String, IProject>();

        remoteProjectID = data.get(0).getProjectID();
        remoteProjectName = data.get(0).getProjectName();

        selectProjectPage = new SelectProjectPage(SELECT_PROJECT_PAGE_ID,
            remoteProjectName, remoteProjectName,
            workspace.getLocation().toOSString(), selectProjectsPageListener);

        registerPage(selectProjectPage);

        fileListPage = new TextAreaPage(FILE_LIST_PAGE_ID,
            "Local file changes:", fileListPageListener);
        registerPage(fileListPage);

        create();

        negotiation.addCancelListener(cancelListener);
    }

    /**
     * Cancels the wizard and gives an informative error message.
     */
    public void cancelWizard(final JID peer, final String errorMsg,
        NegotiationTools.CancelLocation type) {
        final String message = "Wizard canceled " + (type
            .equals(NegotiationTools.CancelLocation.LOCAL) ?
            "locally " :
            "remotely by " + peer);
        UIUtil.invokeLaterIfNeeded(new Runnable() {
            @Override
            public void run() {

                /*
                 *  if we already triggered the negotiation the message will
                 *  be displayed in the trigger logic, so do not popup another dialog here
                 */
                if (!triggered)
                    DialogUtils
                        .showInfo(AddProjectToSessionWizard.this, message,
                            message + (errorMsg != null ?
                                "\n\n" + errorMsg :
                                ""));

                close();
            }
        });

    }

    /**
     * Runs {@link IncomingProjectNegotiation#run(java.util.Map, IProgressMonitor)}
     * as a background task through {@link #runTask(Runnable, String)}.
     * <p/>
     * On success, a success notification is displayed, on error, a dialog is shown.
     */
    private void triggerProjectNegotiation() {

        if (triggered)
            return;

        triggered = true;

        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "Sharing project...", true,
                PerformInBackgroundOption.DEAF) {

                @Override
                public void run(ProgressIndicator indicator) {
                    final ProjectNegotiation.Status status = negotiation
                        .run(localProjects,
                            new ProgessMonitorAdapter(indicator));

                    indicator.stop();

                    UIUtil.invokeLaterIfNeeded(new Runnable() {
                        @Override
                        public void run() {
                            if (status == ProjectNegotiation.Status.ERROR) {
                                DialogUtils.showError(null,
                                    "Error during project negotiation",
                                    "The project could not be shared: "
                                        + negotiation.getErrorMessage());
                            } else if (status == ProjectNegotiation.Status.OK) {
                                NotificationPanel
                                    .showInformation("Project shared",
                                        "Project successfully shared");
                            } else
                                DialogUtils.showError(null,
                                    "Project negotiation aborted",
                                    "Project negotiation was canceled");
                        }
                    });
                }
            });

        close();
    }

    private void prepareFilesChangedPage(
        final Map<String, IProject> projectMapping) {

        final Map<String, FileListDiff> modifiedResources = new HashMap<String, FileListDiff>();

        runTask(new Runnable() {
            @Override
            public void run() {
                modifiedResources.putAll(
                    getModifiedResourcesFromMofifiableProjects(projectMapping,
                        new NullProgressMonitor()));
            }
        }, "Gathering files that have to be modified...");
        fillFileListPage(modifiedResources);
    }

    private void fillFileListPage(Map<String, FileListDiff> modifiedResources) {
        boolean empty = true;
        for (Map.Entry<String, FileListDiff> key : modifiedResources
            .entrySet()) {
            fileListPage.addLine("Project [" + key.getKey() + "]:");
            FileListDiff diff = modifiedResources.get(key.getKey());

            /// TODO folders

            for (String path : diff.getAlteredFiles()) {
                fileListPage.addLine("changed: " + path);
                empty = false;
            }

            for (String path : diff.getRemovedFiles()) {
                fileListPage.addLine("removed: " + path);
                empty = false;
            }

            for (String path : diff.getAddedFiles()) {
                fileListPage.addLine("added: " + path);
                empty = false;
            }
        }

        if (empty) {
            fileListPage.addLine("No files have to be modified.");
        }
    }

    /**
     * Creates a FileListDiff for all projects that will be modified.
     */
    private Map<String, FileListDiff> getModifiedResourcesFromMofifiableProjects(
        Map<String, IProject> projectMapping, IProgressMonitor monitor) {
        monitor.setTaskName("Calculating changed files...");

        final Map<String, FileListDiff> modifiedResources = new HashMap<String, FileListDiff>();
        final Map<String, IProject> modifiedProjects = new HashMap<String, IProject>();

        modifiedProjects.putAll(getModifiedProjects(projectMapping));
        modifiedResources
            .putAll(getModifiedResources(modifiedProjects, monitor));
        return modifiedResources;
    }

    /**
     * Returns a project mapping that contains all projects that will be
     * modified on synchronization.
     * <p/>
     * Currently these are simply all projects from projectMapping.
     * <p/>
     * FIXME: Add a check for non-overwritable projects.
     */
    private Map<String, IProject> getModifiedProjects(
        Map<String, IProject> projectMapping) {
        Map<String, IProject> modifiedProjects = new HashMap<String, IProject>();

        for (Map.Entry<String, IProject> entry : projectMapping.entrySet()) {
            //TODO: Add check for non-overwritable projects
            modifiedProjects.put(entry.getKey(), entry.getValue());
        }

        return modifiedProjects;
    }

    /**
     * Returns all modified resources (either changed or deleted) for the
     * current project mapping.
     * <p/>
     * <b>Important:</b> Do not call this inside the UI Thread. This is a long
     * running operation !
     */
    private Map<String, FileListDiff> getModifiedResources(
        Map<String, IProject> projectMapping, IProgressMonitor monitor) {
        Map<String, FileListDiff> modifiedResources = new HashMap<String, FileListDiff>();

        final ISarosSession session = sessionManager.getSession();

        // FIXME the wizard should handle the case that the session may stop in
        // the meantime !

        if (session == null) {
            throw new IllegalStateException("no session running");
        }

        SubProgressMonitor subMonitor = new SubProgressMonitor(monitor,
            projectMapping.size());

        subMonitor
            .setTaskName("\"Searching for files that will be modified...\",");

        for (Map.Entry<String, IProject> entry : projectMapping.entrySet()) {

            String projectID = entry.getKey();
            IProject project = entry.getValue();

            try {

                final ProjectNegotiationData data = negotiation.getProjectNegotiationData(projectID);

                if (data.isPartial())
                    throw new IllegalStateException("partial sharing is not supported");

                FileList localFileList = FileListFactory
                    .createFileList(project, null, checksumCache,
                        new SubProgressMonitor(monitor, 1,
                            SubProgressMonitor.SUPPRESS_SETTASKNAME));

                final FileListDiff diff = FileListDiff.diff(localFileList,
                    data.getFileList(),
                    false);

                if (!diff.getRemovedFolders().isEmpty()
                    || !diff.getRemovedFiles().isEmpty()
                    || !diff.getAlteredFiles().isEmpty()) {
                    modifiedResources.put(project.getName(), diff);
                }

            } catch (IOException e) {
                LOG.warn("could not refresh project: " + project, e);
            }
        }
        return modifiedResources;
    }
}
