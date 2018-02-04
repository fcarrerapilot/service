package com.playa.admin.app;

import org.apache.log4j.Logger;

public class BusesFenixApp {

    private static final Logger LOGGER = LoggerFactory.getLogger(BusesFenixApp.class);

    private static final String BUSES_PACKAGE = "com.despegar.buses.fenix";

    private static final ArrayList<String> excludePatterns = Lists.newArrayList("/buses-fenix/unsDoPush/status",
        "/buses-fenix/version");

    private static final String basePath = "/*";

    /**
     * Server startup
     * @param args args[0]:Boolean. Start h2 server?
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        LOGGER.info("BUSES FENIX SERVER - STARTING...");
        File file = getFile();

        Config root = ConfigFactory.parseFile(file);
        Config server = root.getConfig("server");
        String[] profiles = server.getStringList("profiles").toArray(ArrayUtils.EMPTY_STRING_ARRAY);

        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.scan(BUSES_PACKAGE);
        context.getEnvironment().setActiveProfiles(profiles);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        for (Config c : server.getConfigList("dispatcher_servlets")) {

            DispatcherServlet dispatcherServlet = new DispatcherServlet(context);
            dispatcherServlet.setDispatchOptionsRequest(true);
            dispatcherServlet.setThrowExceptionIfNoHandlerFound(true);

            ServletHolder servletHolder = new ServletHolder(dispatcherServlet);

            for (Object relativePath : c.getAnyRefList("paths")) {
                ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
                servletHandler.addEventListener(new ContextLoaderListener(context));

                servletHandler.setContextPath(relativePath.toString());

                servletHandler.addServlet(servletHolder, basePath);
                if (DependencyUtils.isClassPresent("com.despegar.library.routing.RSD")) {
                    servletHandler.addFilter(new FilterHolder(new RoutingFilter()), basePath,
                        EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
                }

                GzipHandler gzipHandler = new GzipHandler();
                gzipHandler.setIncludedMimeTypes("application/json");
                gzipHandler.setIncludedPaths();
                contexts.addHandler(gzipHandler);

                servletHandler.addFilter(new FilterHolder(new LoggedServiceFilter(excludePatterns)), basePath,
                    EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

                servletHandler.addFilter(new FilterHolder(new ShallowEtagHeaderFilter()), basePath,
                    EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

                loadFilters(servletHandler, server);

                contexts.addHandler(servletHandler);
            }
        }

        startH2Server(args);

        Server s = new Server(server.getInt("port"));

        for (Connector y : s.getConnectors()) {
            for (ConnectionFactory x : y.getConnectionFactories()) {
                if (x instanceof HttpConnectionFactory) {
                    ((HttpConnectionFactory) x).getHttpConfiguration().setSendServerVersion(false);
                }
            }
        }

        s.setHandler(contexts);
        s.start();
        LOGGER.info("BUSES FENIX SERVER - STARTED");
        s.join();
    }

    private static File getFile() throws IOException {
        ClassPathResource classPathResource = new ClassPathResource("conf/env/startup.conf");
        File file = classPathResource.getFile();
        if (!file.exists()) {
            classPathResource = new ClassPathResource("startup.conf");
            file = classPathResource.getFile();
            if (!file.exists()) {
                throw new RuntimeException(
                    "startup.conf file not found in classpath (it should be in the root or inside conf/env/) -- I don't know how to start dammit!");
            }
        }
        return file;
    }

    private static void startH2Server(String[] args) throws SQLException {
        if (args.length > 0) {
            // args [0] start server test
            Boolean start = Boolean.valueOf(args[0]);
            if (start) {
                LOGGER.info("Starting H2 Server...");
                org.h2.tools.Server.createWebServer("-web").start();
                LOGGER.info("H2 Server STARTED");
            }
        }
    }

    private static void loadFilters(ServletContextHandler servletHandler, Config server) {
        LOGGER.info(String.format("Loading servlet %s filters", servletHandler.getDisplayName()));
        for (Config filterConfig : server.getConfigList("filters")) {
            FilterHolder filterHolder = new FilterHolder();
            filterHolder.setClassName(filterConfig.getString("class"));
            filterHolder.setDisplayName(filterConfig.getString("name"));
            LOGGER.debug(String.format("Loading filter %s for servlet %s", filterConfig.getString("class"),
                servletHandler.getDisplayName()));

            List<? extends Object> params = filterConfig.getAnyRefList("params");
            Integer elements = params.size() % 2;
            if (elements == 0) {
                for (int i = 0; i < params.size(); i += 2) {
                    Object key = params.get(i);
                    Object value = params.get(i + 1);
                    filterHolder.setInitParameter(key.toString(), value.toString());
                }
            }
            servletHandler.addFilter(filterHolder, basePath, EnumSet.of(DispatcherType.REQUEST));
        }
        LOGGER.info(String.format("All filters loaded for servlet %s", servletHandler.getDisplayName()));
    }
}
