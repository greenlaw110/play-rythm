package com.greenlaw110.rythm.play;

import com.greenlaw110.rythm.IByteCodeHelper;
import com.greenlaw110.rythm.IHotswapAgent;
import com.greenlaw110.rythm.Rythm;
import com.greenlaw110.rythm.RythmEngine;
import com.greenlaw110.rythm.logger.ILogger;
import com.greenlaw110.rythm.logger.ILoggerFactory;
import com.greenlaw110.rythm.play.parsers.AbsoluteUrlReverseLookupParser;
import com.greenlaw110.rythm.play.parsers.GroovyVerbatimTagParser;
import com.greenlaw110.rythm.play.parsers.MessageLookupParser;
import com.greenlaw110.rythm.play.parsers.UrlReverseLookupParser;
import com.greenlaw110.rythm.resource.ITemplateResource;
import com.greenlaw110.rythm.spi.IParserFactory;
import com.greenlaw110.rythm.spi.ITemplateClassEnhancer;
import com.greenlaw110.rythm.template.ITemplate;
import com.greenlaw110.rythm.utils.IImplicitRenderArgProvider;
import com.greenlaw110.rythm.utils.IRythmListener;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.classloading.ApplicationClasses;
import play.classloading.HotswapAgent;
import play.exceptions.ConfigurationException;
import play.exceptions.UnexpectedException;
import play.mvc.Scope;
import play.templates.Template;
import play.vfs.VirtualFile;

import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.*;

public class RythmPlugin extends PlayPlugin {
    public static final String VERSION = "0.9.2";

    public static void info(String msg, Object... args) {
        Logger.info(msg_(msg, args));
    }
    
    public static void info(Throwable t, String msg, Object... args) {
        Logger.info(t, msg_(msg, args));
    }
    
    public static void debug(String msg, Object... args) {
        Logger.debug(msg_(msg, args));
    }
    
    public static void debug(Throwable t, String msg, Object... args) {
        Logger.debug(t, msg_(msg, args));
    }
    
    public static void trace(String msg, Object... args) {
        Logger.trace(msg_(msg, args));
    }
    
    public static void trace(Throwable t, String msg, Object... args) {
        Logger.warn(t, msg_(msg, args));
    }

    public static void warn(String msg, Object... args) {
        Logger.warn(msg_(msg, args));
    }

    public static void warn(Throwable t, String msg, Object... args) {
        Logger.warn(t, msg_(msg, args));
    }

    public static void error(String msg, Object... args) {
        Logger.error(msg_(msg, args));
    }

    public static void error(Throwable t, String msg, Object... args) {
        Logger.error(t, msg_(msg, args));
    }

    public static void fatal(String msg, Object... args) {
        Logger.fatal(msg_(msg, args));
    }

    public static void fatal(Throwable t, String msg, Object... args) {
        Logger.fatal(t, msg_(msg, args));
    }

    private static String msg_(String msg, Object... args) {
        return String.format("RythmPlugin-" + VERSION + "> %1$s",
                String.format(msg, args));
    }

    public static RythmEngine engine;
    
    public static enum EngineType {
        rythm, system;
        public static EngineType parseEngineType(String s) {
            if ("rythm".equalsIgnoreCase(s)) return rythm;
            else if ("system".equalsIgnoreCase(s) || "groovy".equalsIgnoreCase(s)) return system;
            else {
                throw new ConfigurationException(String.format("unrecongized engine type[%s] found, please use either \"rythm\" or \"system\"", s));
            }
        }
    }

    public static EngineType defaultEngine = EngineType.system;
    public static boolean underscoreImplicitVariableName = false;
    public static boolean refreshOnRender = true;
    public static String templateRoot = "app/views";
    public static String tagRoot = "app/views/tags/rythm";
    
    public static List<ImplicitVariables.Var> implicitRenderArgs = new ArrayList<ImplicitVariables.Var>();
    
    public static void registerImplicitRenderArg(final String name, final String type) {
        implicitRenderArgs.add(new ImplicitVariables.Var(name, type) {
            @Override
            protected Object evaluate() {
                return Scope.RenderArgs.current().get(name());
            }
        });
    }
    
    @Override
    public void onConfigurationRead() {
        Properties playConf = Play.configuration;
        
        // special configurations
        defaultEngine = EngineType.parseEngineType(playConf.getProperty("rythm.default.engine", "system"));
        debug("default template engine configured to: %s", defaultEngine);
        underscoreImplicitVariableName = Boolean.parseBoolean(playConf.getProperty("rythm.implicitVariable.underscore", "false"));
        refreshOnRender = Boolean.parseBoolean(playConf.getProperty("rythm.resource.refreshOnRender", "true"));

        Properties p = new Properties();

        // set default configurations
        // p.put("rythm.root", new File(Play.applicationPath, "app/views"));
        // p.put("rythm.tag.root", new File(Play.applicationPath, tagRoot));
        p.put("rythm.tag.autoscan", false); // we want to scan tag folder coz we have Virtual Filesystem
        p.put("rythm.classLoader.parent", Play.classloader);
        p.put("rythm.resource.refreshOnRender", "true");
        p.put("rythm.resource.loader", new VirtualFileTemplateResourceLoader());
        p.put("rythm.classLoader.byteCodeHelper", new IByteCodeHelper() {
            @Override
            public byte[] findByteCode(String typeName) {
                ApplicationClasses classBag = Play.classes;
                if (classBag.hasClass(typeName)) {
                    ApplicationClasses.ApplicationClass applicationClass = classBag.getApplicationClass(typeName);
                    return applicationClass.enhancedByteCode;
                } else {
                    return null;
                }
            }
        });
        p.put("rythm.logger.factory", new ILoggerFactory() {
            @Override
            public ILogger getLogger(Class<?> clazz) {
                return PlayRythmLogger.instance;
            }
        });
        p.put("rythm.enableJavaExtensions", true); // enable java extension by default

        // handle implicit render args
        p.put("rythm.implicitRenderArgProvider", new IImplicitRenderArgProvider() {
            @Override
            public Map<String, ?> getRenderArgDescriptions() {
                Map<String, Object> m = new HashMap<String, Object>();
                // App registered render args
                for (ImplicitVariables.Var var: implicitRenderArgs) {
                    m.put(var.name(), var.type);
                }
                // Play default render args
                for (ImplicitVariables.Var var: ImplicitVariables.vars) {
                    m.put(var.name(), var.type);
                }
                return m;
            }

            @Override
            public void setRenderArgs(ITemplate template) {
                Map<String, Object> m = new HashMap<String, Object>();
                // some system implicit render args are not set, so we need to set them here.
                for (ImplicitVariables.Var var: ImplicitVariables.vars) {
                    m.put(var.name(), var.evaluate());
                }
                // application render args should already be set in controller methods
                template.setRenderArgs(m);
            }

            @Override
            public List<String> getImplicitImportStatements() {
                return Arrays.asList(new String[]{"controllers.*", "models.*"});
            }
        });
        debug("Implicit render variables set up");

        // set user configurations - coming from application.conf
        for (String key: playConf.stringPropertyNames()) {
            if (key.startsWith("rythm.")) {
                p.setProperty(key, playConf.getProperty(key));
            }
        }
        debug("User defined rythm properties configured");
        
        // set template root
        templateRoot = p.getProperty("rythm.root", templateRoot);
        p.put("rythm.root", new File(Play.applicationPath, templateRoot));
        if (Logger.isDebugEnabled()) debug("rythm template root set to: %s", p.get("rythm.root"));

        // set tag root
        tagRoot = p.getProperty("rythm.tag.root", tagRoot);
        if (tagRoot.endsWith("/")) tagRoot = tagRoot.substring(0, tagRoot.length() - 1);
        p.put("rythm.tag.root", new File(Play.applicationPath, tagRoot));
        if (Logger.isDebugEnabled()) debug("rythm tag root set to %s", p.get("rythm.tag.root"));
        
        // set tmp dir
        File tmpDir = new File(Play.tmpDir, "rythm");
        tmpDir.mkdirs();
        p.put("rythm.tmpDir", tmpDir);
        if (Logger.isDebugEnabled()) debug("rythm tmp dir set to %s", p.get("rythm.tmpDir"));

        // always get "java.lang.UnsupportedOperationException: class redefinition failed: attempted to change the schema" exception
        // from the hotswapAgent
        boolean useHotswapAgent = Boolean.valueOf(playConf.getProperty("rythm.useHotswapAgent", "false"));
        if (useHotswapAgent) {
            p.put("rythm.classLoader.hotswapAgent", new IHotswapAgent() {
                @Override
                public void reload(ClassDefinition... definitions) throws UnmodifiableClassException, ClassNotFoundException {
                    HotswapAgent.reload(definitions);
                }
            });
        }

        p.put("rythm.mode", Play.mode.isDev() ? Rythm.Mode.dev : Rythm.Mode.prod);

        if (null == engine) {
            engine = new RythmEngine(p);
            engine.registerListener(new IRythmListener() {
                @Override
                public void onRender(ITemplate template) {
                    Map<String, Object> m = new HashMap<String, Object>();
                    for (ImplicitVariables.Var var: ImplicitVariables.vars) {
                        m.put(var.name(), var.evaluate());
                    }
                    template.setRenderArgs(m);
                }
            });
            debug("Implicit render variables runtime provider set up");
//            engine.registerTemplateClassEnhancer(new ITemplateClassEnhancer() {
//                @Override
//                public byte[] enhance(String className, byte[] classBytes) throws  Exception {
//                    ApplicationClasses.ApplicationClass applicationClass = new ApplicationClasses.ApplicationClass();
//                    applicationClass.javaByteCode = classBytes;
//                    applicationClass.enhancedByteCode = classBytes;
//                    File f = File.createTempFile("rythm_", className.contains("$") ? "$1" : "" + ".java", Play.tmpDir);
//                    applicationClass.javaFile = VirtualFile.open(f);
//                    new TemplatePropertiesEnhancer().enhanceThisClass(applicationClass);
//                    return applicationClass.enhancedByteCode;
//                }
//            });
//            debug("Template class enhancer registered");
            Rythm.engine = engine;

            IParserFactory[] factories = {new AbsoluteUrlReverseLookupParser(), new UrlReverseLookupParser(),
                    new MessageLookupParser(), new GroovyVerbatimTagParser()};
            engine.getExtensionManager().registerUserDefinedParsers(factories);
            debug("Play specific parser registered");
        } else {
            engine.init(p);
        }

        info("template engine initialized");
    }
    
    @Override
    public void onApplicationStart() {
        long l = System.currentTimeMillis();
        RythmTemplateLoader.buildBlackWhiteList();
        debug("%sms to built up black/white list", System.currentTimeMillis() - l);
        l = System.currentTimeMillis();
        FastTagBridge.registerFastTags(engine);
        registerJavaTags(engine);
        debug("%sms to register fast tags", System.currentTimeMillis() - l);

        if (engine.enableJavaExtensions()) {
            l = System.currentTimeMillis();
            JavaExtensionBridge.registerPlayBuiltInJavaExtensions(engine);
            debug("%sms to register java extension", System.currentTimeMillis() - l);
        }

        l = System.currentTimeMillis();
        RythmTemplateLoader.scanTagFolder();
        debug("%sms to load Rythm tags", System.currentTimeMillis() - l);
    }
    
    private void registerJavaTags(RythmEngine engine) {
        // -- register application java tags
        List<ApplicationClasses.ApplicationClass> classes = Play.classes.getAssignableClasses(FastRythmTag.class);
        for (ApplicationClasses.ApplicationClass ac: classes) {
            registerJavaTag(ac.javaClass, engine);
        }
        
        // -- register PlayRythm build-in tags
        Class<?>[] ca = FastRythmTags.class.getDeclaredClasses();
        for (Class<?> c: ca) {
            registerJavaTag(c, engine);
        }
    }
    
    private void registerJavaTag(Class<?> jc, RythmEngine engine) {
        int flag = jc.getModifiers();
        if (Modifier.isAbstract(flag)) return;
        try {
            Constructor<?> c = jc.getConstructor(new Class[]{});
            c.setAccessible(true);
            FastRythmTag tag = (FastRythmTag)c.newInstance();
            engine.registerTag(tag);
        } catch (Exception e) {
            throw new UnexpectedException("Error initialize JavaTag: " + jc.getName(), e);
        }
    }

    public static final Template VOID_TEMPLATE = new Template() {
        @Override
        public void compile() {
            //
        }
        @Override
        protected String internalRender(Map<String, Object> args) {
            throw new UnexpectedException("It's not supposed to be called");
        }
    };

    @Override
    public Template loadTemplate(VirtualFile file) {
        if (null == engine) {
            // in prod mode this method is called in preCompile() when onConfigurationRead() has not been called yet
            onConfigurationRead();
        }
        return RythmTemplateLoader.loadTemplate(file);
    }

    @Override
    public void detectChange() {
        if (!refreshOnRender) engine.classLoader.detectChanges();
    }

}
