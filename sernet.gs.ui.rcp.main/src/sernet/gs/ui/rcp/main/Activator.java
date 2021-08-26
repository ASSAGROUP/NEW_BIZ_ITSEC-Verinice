/*******************************************************************************
 * Copyright (c) 2009 Alexander Koderman <ak[at]sernet[dot]de>.
 * This program is free software: you can redistribute it and/or 
 * modify it under the terms of the GNU Lesser General Public License 
 * as published by the Free Software Foundation, either version 3 
 * of the License, or (at your option) any later version.
 *     This program is distributed in the hope that it will be useful,    
 * but WITHOUT ANY WARRANTY; without even the implied warranty 
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  
 * See the GNU Lesser General Public License for more details.
 *     You should have received a copy of the GNU Lesser General Public 
 * License along with this program. 
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     Alexander Koderman <ak[at]sernet[dot]de> - initial API and implementation
 ******************************************************************************/
package sernet.gs.ui.rcp.main;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import org.apache.commons.io.FilenameUtils;
import org.apache.log4j.Logger;
import org.eclipse.core.net.proxy.IProxyData;
import org.eclipse.core.net.proxy.IProxyService;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.equinox.internal.p2.ui.ProvUI;
import org.eclipse.equinox.internal.p2.ui.ProvUIActivator;
import org.eclipse.equinox.p2.repository.IRepository;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.equinox.p2.ui.ProvisioningUI;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

import sernet.gs.ui.rcp.main.bsi.model.BSIConfigFactory;
import sernet.gs.ui.rcp.main.bsi.model.BSIEntityResolverFactory;
import sernet.gs.ui.rcp.main.bsi.model.RcpLayoutConfig;
import sernet.gs.ui.rcp.main.common.model.CnAElementFactory;
import sernet.gs.ui.rcp.main.common.model.CnAElementHome;
import sernet.gs.ui.rcp.main.common.model.IModelLoadListener;
import sernet.gs.ui.rcp.main.logging.LoggerInitializer;
import sernet.gs.ui.rcp.main.preferences.PreferenceConstants;
import sernet.gs.ui.rcp.main.service.ServiceFactory;
import sernet.hui.common.VeriniceContext;
import sernet.hui.common.connect.ResolverFactoryRegistry;
import sernet.verinice.interfaces.CommandException;
import sernet.verinice.interfaces.ICommandService;
import sernet.verinice.interfaces.IInternalServer;
import sernet.verinice.interfaces.IInternalServerStartListener;
import sernet.verinice.interfaces.ILogPathService;
import sernet.verinice.interfaces.IMain;
import sernet.verinice.interfaces.IReportLocalTemplateDirectoryService;
import sernet.verinice.interfaces.VersionConstants;
import sernet.verinice.interfaces.licensemanagement.ILicenseManagementService;
import sernet.verinice.interfaces.oda.IVeriniceOdaDriver;
import sernet.verinice.interfaces.report.IReportService;
import sernet.verinice.iso27k.rcp.JobScheduler;
import sernet.verinice.model.bp.elements.BpModel;
import sernet.verinice.model.bsi.BSIModel;
import sernet.verinice.model.catalog.CatalogModel;
import sernet.verinice.model.iso27k.ISO27KModel;
import sernet.verinice.rcp.Preferences;
import sernet.verinice.rcp.ReportTemplateSyncer;
import sernet.verinice.rcp.StartupImporter;
import sernet.verinice.rcp.jobs.VeriniceWorkspaceJob;
import sernet.verinice.service.commands.migration.DbVersion;
import sernet.verinice.service.model.IObjectModelService;
import sernet.verinice.service.parser.BSIConfigurationRemoteSource;
import sernet.verinice.service.parser.GSScraperUtil;

/**
 * The activator class controls the plug-in life cycle
 */
@SuppressWarnings("restriction")
public class Activator extends AbstractUIPlugin implements IMain {

    private static final Logger LOG = Logger.getLogger(Activator.class);

    public static final String PLUGIN_ID = "sernet.gs.ui.rcp.main"; //$NON-NLS-1$

    private static final String PAX_WEB_SYMBOLIC_NAME = "org.ops4j.pax.web.pax-web-bundle"; //$NON-NLS-1$

    private static final String REPORT_SERVICE_SYMBOLIC_NAME = "sernet.verinice.report.service"; //$NON-NLS-1$

    private static final String LOCAL_UPDATE_SITE_URL = "/Verinice-Update-Site-2010"; //$NON-NLS-1$

    public static final String UPDATE_SITE_URL = "https://update.verinice.org/pub/verinice/update/subscription"; //$NON-NLS-1$

    public static final String DERBY_LOG_FILE_PROPERTY = "derby.stream.error.file"; //$NON-NLS-1$

    public static final String DERBY_LOG_FILE = "verinice-derby.log"; //$NON-NLS-1$

    // The shared instance
    private static Activator plugin;

    private static VeriniceContext.State state;

    private IInternalServer internalServer;

    private IVeriniceOdaDriver odaDriver;

    private BundleContext context;

    private boolean runsAsApplication = false;

    private boolean standalone = false;

    private ServiceTracker templateDirTracker;

    private ServiceTracker proxyTracker;

    private WorkspaceJob reindexJob;

    public Activator() {
        plugin = this;
    }

    public static IWorkbenchPage getActivePage() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return null;
        }

        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            return null;
        }

        return page;
    }

    /**
     * Brings the bundle (not the whole RCP application) in a usable state.
     */
    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);

        this.context = context;

        if (LOG.isInfoEnabled()) {
            final Bundle bundle = context.getBundle();
            LOG.info("Starting bundle " + bundle.getSymbolicName() + " " + bundle.getVersion()); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Makes a representation of this bundle as a service available.
        context.registerService(IMain.class.getName(), this, null);
        LoggerInitializer loggerInitializer = LoggerInitializer.setupLogFilePath();
        context.registerService(ILogPathService.class.getName(), loggerInitializer, null);

        // Set the derby log file path
        System.setProperty(DERBY_LOG_FILE_PROPERTY,
                loggerInitializer.getLogDirectory() + File.separatorChar + DERBY_LOG_FILE); // $NON-NLS-1$

        templateDirTracker = new ServiceTracker(context,
                IReportLocalTemplateDirectoryService.class.getName(), null);
        templateDirTracker.open();

    }

    /**
     * Starts methods and jobs which are needed for the application. See
     * sernet.verinice.iso27k.rcp.action.Initializer, which is executed after
     * code in this Activator.
     * 
     * <p>
     * Note: This method can only be called once.
     * </p>
     * 
     * <p>
     * Note: This method is solely to be called from {@link Application}.
     * </p>
     * 
     * <p>
     * Note: This method is *NOT* to be called when verinice is being used as a
     * library (IOW when being run during the report design phase).
     * </p>
     * 
     * @throws BundleException
     */
    void startApplication() throws BundleException {
        runsAsApplication = true;

        Bundle bundle = Platform.getBundle(REPORT_SERVICE_SYMBOLIC_NAME);
        if (bundle == null) {
            LOG.warn("Report service bundle is not available!"); //$NON-NLS-1$
        } else {
            bundle.start();
        }

        // set workdir preference:
        CnAWorkspace.getInstance().prepareWorkDir();
        if (!prepareReportDirs()) {
            LOG.warn("ReportDirs are not created correclty");
        }
        if (!prepareVNLDir()) {
            LOG.warn("VNL-Dir was not created correctly");
        }

        if (sernet.verinice.rcp.Preferences.isServerMode()) {
            setSystemProxy();
        }

        // set service factory location to local / remote according to
        // preferences:
        standalone = sernet.verinice.rcp.Preferences.isStandalone();

        initializeInternalServer();
        configureInternalServer();

        // prepare client's workspace:
        CnAWorkspace.getInstance().prepare();

        try {
            ServiceFactory.openCommandService();
        } catch (Exception e) {
            // if this fails, try rewriting config:
            LOG.error("Exception while connection to command service, forcing recreation of " //$NON-NLS-1$
                    + "service factory configuration from preferences.", e); //$NON-NLS-1$
            CnAWorkspace.getInstance().prepare(true);
        }

        // When the service factory is initialized the client's work objects can
        // be accessed.
        // The line below initializes the VeriniceContext initially.
        state = ServiceFactory.getClientWorkObjects();
        VeriniceContext.setState(state);

        // Make command service available as an OSGi service
        context.registerService(ICommandService.class.getName(),
                VeriniceContext.get(VeriniceContext.COMMAND_SERVICE), null);

        configureItbpCatalogLoader();

        GSScraperUtil.getInstance().getModel().setLayoutConfig(new RcpLayoutConfig());
        ResolverFactoryRegistry.setResolverFactory(new BSIEntityResolverFactory());

        Job repositoryJob = new Job("add-repository") { //$NON-NLS-1$
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    addUpdateRepository();
                } catch (URISyntaxException e) {
                    LOG.error("Error while adding update repository."); //$NON-NLS-1$
                }
                return Status.OK_STATUS;
            }
        };
        repositoryJob.schedule();

        StartupImporter.importVna();

        ReportTemplateSyncer.sync();

        // Log the system and application configuration
        ConfigurationLogger.logSystemProperties();
        ConfigurationLogger.logApplicationProperties();
        ConfigurationLogger.logProxyPreferences();
        if (CnAElementFactory.isModelLoaded() || CnAElementFactory.isIsoModelLoaded()) {
            initObjectModelService();
        } else {
            IModelLoadListener loadListener = new IModelLoadListener() {

                @Override
                public void loaded(ISO27KModel model) {
                    // do nothing
                }

                @Override
                public void loaded(BSIModel model) {
                    initObjectModelService();
                    CnAElementFactory.getInstance().removeLoadListener(this);
                }

                @Override
                public void closed(BSIModel model) {
                    // do nothing
                }

                @Override
                public void loaded(BpModel model) {
                    // do nothing
                }

                @Override
                public void loaded(CatalogModel model) {
                    // do nothing
                }
            };
            CnAElementFactory.getInstance().addLoadListener(loadListener);
        }
    }

    public void configureInternalServer() {
        setGSDSCatalog();

        // Provide initial DB connection details to server.
        internalServer.configureDatabase(getPreferences().getString(PreferenceConstants.DB_URL),
                getPreferences().getString(PreferenceConstants.DB_USER),
                getPreferences().getString(PreferenceConstants.DB_PASS),
                PreferenceConstants.DB_DRIVER_DERBY,
                getPreferences().getString(PreferenceConstants.DB_DIALECT));
        internalServer.configureSearch(
                getPreferences().getBoolean(PreferenceConstants.SEARCH_DISABLE),
                getPreferences().getBoolean(PreferenceConstants.SEARCH_INDEX_ON_STARTUP));
    }

    private void configureItbpCatalogLoader() {
        if (isStandalone()) {
            GSScraperUtil.getInstance().getModel()
                    .setBSIConfig(BSIConfigFactory.createStandaloneConfig());
        } else {
            GSScraperUtil.getInstance().getModel().setBSIConfig(new BSIConfigurationRemoteSource());
        }
    }

    private void initObjectModelService() {
        VeriniceWorkspaceJob job = new VeriniceWorkspaceJob("Load objectModelService",
                "error while loading objectModelService") {
            @Override
            protected void doRunInWorkspace() {
                inheritVeriniceContextState();
                IObjectModelService objectModelService = ServiceFactory.lookupObjectModelService();
                long time = System.currentTimeMillis();
                objectModelService.init();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("took " + (System.currentTimeMillis() - time)
                            + " msec to load Service");
                }
            }
        };
        JobScheduler.scheduleInitJob(job);
    }

    private void setGSDSCatalog() {
        try {
            if (Preferences.isBpCatalogLoadedFromZipFile()) {
                internalServer.setGSCatalogURL(propertyToURL(PreferenceConstants.BSIZIPFILE));
            } else {
                internalServer.setGSCatalogURL(propertyToURL(PreferenceConstants.BSIDIR));
            }
            internalServer.setDSCatalogURL(propertyToURL(PreferenceConstants.DSZIPFILE));
        } catch (MalformedURLException mfue) {
            LOG.warn("Error while setting base protection catalog pathes", mfue); //$NON-NLS-1$
        }
    }

    public URL propertyToURL(String propertyKey) throws MalformedURLException {
        return new File(getPreferences().getString(propertyKey)).toURI().toURL();
    }

    private void initializeInternalServer() throws BundleException {
        Bundle bundle;
        // Start server only when it is needed.
        if (standalone) {
            bundle = Platform.getBundle("sernet.gs.server"); //$NON-NLS-1$
            if (bundle == null) {
                LOG.warn(
                        "verinice server bundle is not available. Assuming it is started separately."); //$NON-NLS-1$
            } else if (bundle.getState() == Bundle.INSTALLED
                    || bundle.getState() == Bundle.RESOLVED) {
                LOG.debug("Manually starting GS Server"); //$NON-NLS-1$
                bundle.start();
            }

            ServiceReference sr = context.getServiceReference(IInternalServer.class.getName());
            if (sr == null) {
                throw new IllegalStateException("Cannot retrieve internal server service."); //$NON-NLS-1$
            }

            internalServer = (IInternalServer) context.getService(sr);

        } else {
            internalServer = new ServerDummy();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Internal server is not used."); //$NON-NLS-1$
            }
            // Pax Web Http Service (embedded jetty) is starting automatically
            // after loading and starting
            // the bundle PAX_WEB_SYMBOLIC_NAME which you can not prevent
            // When internal server is not used bundle and Http Service is
            // stopped here
            try {
                Bundle paxWebBundle = Platform.getBundle(PAX_WEB_SYMBOLIC_NAME);
                if (paxWebBundle != null) {
                    paxWebBundle.stop();
                }
            } catch (Exception e) {
                LOG.error("Error while stopping pax-web http-service.", e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Load proxy parameter from the preferences and set these parameters as
     * system properties
     * 
     * @throws URISyntaxException
     */
    private void setSystemProxy() {
        try {
            URI serverUri = new URI(getPreferences().getString(PreferenceConstants.VNSERVER_URI));
            IProxyService proxyService = getProxyService();
            IProxyData[] proxyDataForHost = proxyService.select(serverUri);
            if (proxyDataForHost == null || proxyDataForHost.length == 0) {
                clearSystemProxy();
            } else {
                setSystemProxy(proxyDataForHost);
            }
        } catch (Exception t) {
            LOG.error("Error while setting proxy.", t); //$NON-NLS-1$
        }
    }

    private void setSystemProxy(IProxyData[] proxyDataForHost) {
        for (IProxyData data : proxyDataForHost) {
            if (data.getHost() != null) {
                System.setProperty("http.proxySet", "true"); //$NON-NLS-1$ //$NON-NLS-2$
                System.setProperty("http.proxyHost", data.getHost()); //$NON-NLS-1$
                System.setProperty("http.proxyPort", String.valueOf(data.getPort())); //$NON-NLS-1$
                if (data.getUserId() != null && !data.getUserId().isEmpty()) {
                    System.setProperty("http.proxyName", data.getUserId()); //$NON-NLS-1$
                }
                if (data.getPassword() != null && !data.getPassword().isEmpty()) {
                    System.setProperty("http.proxyPassword", data.getPassword()); //$NON-NLS-1$
                }
            }
        }
    }

    private void clearSystemProxy() {
        System.setProperty("http.proxySet", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        System.clearProperty("http.proxyHost"); //$NON-NLS-1$
        System.clearProperty("http.proxyPort"); //$NON-NLS-1$
        System.clearProperty("http.proxyName"); //$NON-NLS-1$
        System.clearProperty("http.proxyPassword"); //$NON-NLS-1$
    }

    private IPreferenceStore getPreferences() {
        return Activator.getDefault().getPreferenceStore();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        CnAElementHome.getInstance().close();
        plugin = null;
        if (proxyTracker != null) {
            proxyTracker.close();
        }
        joinReindexJob();
        super.stop(context);
    }

    private void joinReindexJob() throws InterruptedException {
        if (reindexJob != null) {
            reindexJob.cancel();
            reindexJob.join();
            reindexJob = null;
        }
    }

    /**
     * @return The shared instance
     */
    public static Activator getDefault() {
        return plugin;
    }

    public IInternalServer getInternalServer() {
        return internalServer;
    }

    public IVeriniceOdaDriver getOdaDriver() {
        return odaDriver;
    }

    public static void checkDbVersion() throws CommandException {
        final boolean[] done = new boolean[1];
        final int sleepTime = 1000;
        final int maxStartTime = 30000;
        done[0] = false;
        Thread timeout = new Thread() {
            @Override
            public void run() {
                long startTime = System.currentTimeMillis();
                while (!done[0]) {
                    try {
                        sleep(sleepTime);
                        long now = System.currentTimeMillis();
                        if (now - startTime > maxStartTime) {
                            ExceptionUtil.log(
                                    new Exception(sernet.gs.ui.rcp.main.Messages.Activator_8),
                                    sernet.gs.ui.rcp.main.Messages.Activator_10);
                            return;
                        }
                    } catch (InterruptedException e) {
                        // Restore interrupted state...
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        timeout.start();
        try {
            DbVersion command = new DbVersion(VersionConstants.COMPATIBLE_CLIENT_VERSION);
            ServiceFactory.lookupCommandService().executeCommand(command);
            done[0] = true;
        } catch (CommandException e) {
            done[0] = true;
            throw e;
        } catch (RuntimeException re) {
            done[0] = true;
            throw re;
        }
    }

    private void addUpdateRepository() throws URISyntaxException {
        URI repoUri = null;
        String name = null;
        if (sernet.verinice.rcp.Preferences.isServerMode()) {
            repoUri = new URI(createUpdateSiteUrl(
                    getPreferences().getString(PreferenceConstants.VNSERVER_URI)));
            name = Messages.Activator_4;
        } else {
            repoUri = new URI(UPDATE_SITE_URL);
            name = Messages.Activator_43;
        }
        removeRepository();

        // Load repo
        try {
            getMetadataRepositoryManager().addRepository(repoUri);
            getMetadataRepositoryManager().setRepositoryProperty(repoUri, IRepository.PROP_NAME,
                    name);

            if (LOG.isDebugEnabled()) {
                LOG.debug("MetadataRepository added: " + repoUri); //$NON-NLS-1$
            }
            getArtifactRepositoryManager().addRepository(repoUri);
            getArtifactRepositoryManager().setRepositoryProperty(repoUri, IRepository.PROP_NAME,
                    name);
            if (LOG.isDebugEnabled()) {
                LOG.debug("ArtifactRepository added: " + repoUri); //$NON-NLS-1$
            }
        } catch (Exception e) {
            LOG.warn("Can not add repository: " + repoUri); //$NON-NLS-1$
            if (LOG.isDebugEnabled()) {
                LOG.debug("stacktrace: ", e); //$NON-NLS-1$
            }
        }
    }

    public IArtifactRepositoryManager getArtifactRepositoryManager() {
        // Load artifact manager
        final ProvisioningUI ui = ProvUIActivator.getDefault().getProvisioningUI();
        return ProvUI.getArtifactRepositoryManager(ui.getSession());
    }

    public IMetadataRepositoryManager getMetadataRepositoryManager() {
        // Load repository manager
        final ProvisioningUI ui = ProvUIActivator.getDefault().getProvisioningUI();
        return ProvUI.getMetadataRepositoryManager(ui.getSession());
    }

    private void removeRepository() {
        URI[] uriArray = getMetadataRepositoryManager()
                .getKnownRepositories(IArtifactRepositoryManager.REPOSITORIES_ALL);
        if (uriArray != null) {
            for (int i = 0; i < uriArray.length; i++) {
                URI uri = uriArray[i];
                if (uri.toString().endsWith(LOCAL_UPDATE_SITE_URL)
                        || UPDATE_SITE_URL.equals(uri.toString())) {
                    getArtifactRepositoryManager().removeRepository(uri);
                    getMetadataRepositoryManager().removeRepository(uri);
                }
            }
        }
    }

    private String createUpdateSiteUrl(String serverUrl) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(serverUrl);
        stringBuilder.append(LOCAL_UPDATE_SITE_URL);
        return stringBuilder.toString();
    }

    /**
     * Returns an image descriptor for the image file at the given plug-in
     * relative path
     * 
     * @param path
     *            the path
     * @return the image descriptor
     */
    public static ImageDescriptor getImageDescriptor(String path) {
        return imageDescriptorFromPlugin(PLUGIN_ID, path);
    }

    /**
     * Initializes the current thread with the VeriniceContext.State of the
     * client application.
     * 
     * <p>
     * Calling this method is needed when the Activator was run on a different
     * thread then the Application class.
     * </p>
     */
    public static void inheritVeriniceContextState() {
        VeriniceContext.setState(state);
    }

    /**
     * Implementation of {@link IInternalServer} which does nothing.
     * 
     * <p>
     * An instance of this class exists when the client does not use the
     * internal server. There are however codepaths where methods from an
     * <code>IInternalServer</code> instance are invoked unconditionally.
     * </p>
     * 
     */
    private static class ServerDummy implements IInternalServer {
        @Override
        public void configureDatabase(String url, String user, String pass, String driver,
                String dialect) {
            // Intentionally do nothing.
        }

        @Override
        public void configureSearch(boolean disable, boolean indexOnStartup) {
            // Intentionally do nothing.
        }

        @Override
        public void start() {
            // Intentionally do nothing.
        }

        @Override
        public void stop() {
            // Intentionally do nothing.
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void setGSCatalogURL(URL url) {
            // Intentionally do nothing.
        }

        @Override
        public void setDSCatalogURL(URL url) {
            // Intentionally do nothing.
        }

        @Override
        public void addInternalServerStatusListener(IInternalServerStartListener listener) {
            // Intentionally do nothing.
        }

        @Override
        public void removeInternalServerStatusListener(IInternalServerStartListener listener) {
            // Intentionally do nothing.
        }
    }

    /**
     * Allows setting the server URI from another bundle (actually it is the ODA
     * driver which does that).
     * 
     * <p>
     * That method is needed during the report design phase to forward the
     * server settings from the designer into the main bundle.
     * </p>
     * 
     * <p>
     * Due to the design of ODA drivers this method is also called when the
     * driver is used through verinice itself (during report generation).
     * However in that case the method call has no effect.
     * </p>
     */
    @Override
    public void updateServerURI(String uri) {
        if (!runsAsApplication) {
            LOG.info(
                    "verinice runs in designer mode - retrieving server configuration from ODA driver."); //$NON-NLS-1$
            ClientPropertyPlaceholderConfigurer.setRemoteServerMode(uri);
            try {
                ServiceFactory.openCommandService();
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
            state = ServiceFactory.getClientWorkObjects();
            VeriniceContext.setState(state);

            // Make command and model service available as an OSGi service
            context.registerService(ICommandService.class.getName(),
                    VeriniceContext.get(VeriniceContext.COMMAND_SERVICE), null);
            context.registerService(IObjectModelService.class.getName(),
                    VeriniceContext.get(VeriniceContext.OBJECT_MODEL_SERVICE), null);
        }
    }

    public boolean isStandalone() {
        return standalone;
    }

    public IProxyService getProxyService() {
        return (IProxyService) getProxyTracker().getService();
    }

    private ServiceTracker getProxyTracker() {
        if (proxyTracker == null) {
            proxyTracker = new ServiceTracker(
                    FrameworkUtil.getBundle(this.getClass()).getBundleContext(),
                    IProxyService.class.getName(), null);
            proxyTracker.open();
        }
        return proxyTracker;
    }

    private boolean prepareReportDirs() {
        return CnAWorkspace.getInstance()
                .createLocalReportTemplateDir(IReportService.VERINICE_REPORTS_LOCAL)
                && CnAWorkspace.getInstance()
                        .createReportTemplateDir(IReportService.VERINICE_REPORTS_REMOTE);
    }

    private boolean prepareVNLDir() {
        String workspace = System.getProperty("osgi.instance.area");
        String vnlPath = FilenameUtils.concat(workspace,
                ILicenseManagementService.VNL_FILE_EXTENSION);
        if (vnlPath.startsWith("file:")) {
            vnlPath = vnlPath.substring(5);
        }
        return CnAWorkspace.getInstance().createLocalReportTemplateDir(vnlPath);
    }

    public IReportLocalTemplateDirectoryService getIReportTemplateDirectoryService() {
        return (IReportLocalTemplateDirectoryService) templateDirTracker.getService();
    }

    public void setReindexJob(WorkspaceJob reindexJob) {
        this.reindexJob = reindexJob;
    }
}
