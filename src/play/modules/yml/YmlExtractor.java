package play.modules.yml;

import java.beans.PropertyVetoException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Id;
import javax.persistence.PersistenceException;

import org.apache.log4j.Level;
import org.hibernate.CallbackException;
import org.hibernate.EmptyInterceptor;
import org.hibernate.collection.PersistentCollection;
import org.hibernate.ejb.Ejb3Configuration;
import org.hibernate.type.Type;

import com.mchange.v2.c3p0.ComboPooledDataSource;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.classloading.ApplicationClassloader;
import play.db.DB;
import play.db.jpa.JPA;
import play.db.jpa.JPAPlugin;
import play.db.jpa.JPASupport;
import play.db.jpa.Model;
import play.exceptions.JPAException;
import play.utils.Utils;

public class YmlExtractor {
    
    public static void main(String[] args) throws Exception {
        // we retrieve parameters
        String filename = "data";
        String output = "conf/";
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                if (args[i].startsWith("--filename=")) {
                    filename = args[i].substring(11);
                }
                if (args[i].startsWith("--output=")) {
                    output = args[i].substring(9);
                }
            }
        }
        
        File root = new File(System.getProperty("application.path"));
        Play.init(root, System.getProperty("play.id", ""));
        
        Ejb3Configuration cfg = new Ejb3Configuration();
        cfg.setDataSource(DB.datasource);
        cfg.setProperty("hibernate.dialect", getDefaultDialect(Play.configuration.getProperty("db.driver")));
        cfg.setProperty("javax.persistence.transaction", "RESOURCE_LOCAL");
        EntityManager em = iniateJPA();
        
        // we create the file
        File file = new File(output + "/" + filename + ".yml");
        FileOutputStream fop = new FileOutputStream(file);
        fop.write("# Generated by logisima-play-yml (http://www.logisima.com) \n".getBytes());

        // we search all entities classes 
        List<Class> entities = Play.classloader.getAnnotatedClasses(Entity.class);
        for (Class entity : entities) {
            Logger.info("Generate YML for class :" + entity.getCanonicalName());
            fop.write(("\n# " + entity.getCanonicalName() + "\n").getBytes());
            // we search all object for the specified class
            JPAPlugin.startTx(true);
            List<JPASupport> objects = (List<JPASupport>) em.createQuery("select e from " + entity.getCanonicalName() + " as e").getResultList();
            for (JPASupport jpaSupport : objects) {
                Logger.info("Generate YML for class id :" + getObjectId(jpaSupport));
                fop.write((jpaSupport.getClass().getCanonicalName() + "(" + getObjectId(jpaSupport) + ")\n").getBytes());
                for (java.lang.reflect.Field field : jpaSupport.getClass().getFields()) {
                    String name = field.getName();
                    String value = "";
                        value = field.toString();
                    
                    fop.write(("\t" + name + ":" + value + "\n").getBytes());
                }
            }
        }
        fop.flush();
        fop.close();
    }
    
    /**
     * Method that return an indentifer for the object.
     *
     * @param Object
     * @return the id field value
     * @throws IllegalAccessException 
     * @throws IllegalArgumentException 
     */
    private static String getObjectId(Object object) throws IllegalArgumentException, IllegalAccessException {
        JPASupport jpaSupport = (JPASupport) object;
        String objectId = null;
        //if the object extend from the play's model class
        if (jpaSupport instanceof Model) {
            //we take the model id
            objectId = ((Model) jpaSupport).getClass().getSimpleName() + "_" + ((Model) jpaSupport).id.toString();
        }
        //else we try to get value of the field with id annotation
        else{
            //we look up for the field with the id annotation
            Field fieldId = null;
            for (java.lang.reflect.Field field : jpaSupport.getClass().getFields()) {
                if (field.getAnnotation(Id.class) != null) {
                    fieldId = field;
                }
            }
            if (fieldId != null){
                objectId = fieldId.get(jpaSupport).toString();
            }
        }

        return objectId;
    }
    
    private static String getDefaultDialect(String driver) {
        if (driver.equals("org.hsqldb.jdbcDriver")) {
            return "org.hibernate.dialect.HSQLDialect";
        } else if (driver.equals("com.mysql.jdbc.Driver")) {
            return "play.db.jpa.MySQLDialect";
        } else {
            String dialect = Play.configuration.getProperty("jpa.dialect");
            if (dialect != null) {
                return dialect;
            }
            throw new UnsupportedOperationException("I do not know which hibernate dialect to use with " +
                    driver + ", use the property jpa.dialect in config file");
        }
    }
    
    private static EntityManager iniateJPA() throws PropertyVetoException{
        Properties p = Play.configuration;
        ComboPooledDataSource ds = new ComboPooledDataSource();
        ds.setDriverClass(p.getProperty("db.driver"));
        ds.setJdbcUrl(p.getProperty("db.url"));
        ds.setUser(p.getProperty("db.user"));
        ds.setPassword(p.getProperty("db.pass"));
        ds.setAcquireRetryAttempts(1);
        ds.setAcquireRetryDelay(0);
        ds.setCheckoutTimeout(Integer.parseInt(p.getProperty("db.pool.timeout", "5000")));
        ds.setBreakAfterAcquireFailure(true);
        ds.setMaxPoolSize(Integer.parseInt(p.getProperty("db.pool.maxSize", "30")));
        ds.setMinPoolSize(Integer.parseInt(p.getProperty("db.pool.minSize", "1")));
        ds.setTestConnectionOnCheckout(true);
        
        List<Class> classes = Play.classloader.getAnnotatedClasses(Entity.class);
        Ejb3Configuration cfg = new Ejb3Configuration();
        cfg.setDataSource(ds);
        if (!Play.configuration.getProperty("jpa.ddl", "update").equals("none")) {
            cfg.setProperty("hibernate.hbm2ddl.auto", Play.configuration.getProperty("jpa.ddl", "update"));
        }
        cfg.setProperty("hibernate.dialect", getDefaultDialect(Play.configuration.getProperty("db.driver")));
        cfg.setProperty("javax.persistence.transaction", "RESOURCE_LOCAL");
        if (Play.configuration.getProperty("jpa.debugSQL", "false").equals("true")) {
            org.apache.log4j.Logger.getLogger("org.hibernate.SQL").setLevel(Level.ALL);
        } else {
            org.apache.log4j.Logger.getLogger("org.hibernate.SQL").setLevel(Level.OFF);
        }
        // inject additional  hibernate.* settings declared in Play! configuration
        cfg.addProperties((Properties) Utils.Maps.filterMap(Play.configuration, "^hibernate\\..*"));

        try {
            Field field = cfg.getClass().getDeclaredField("overridenClassLoader");
            field.setAccessible(true);
            field.set(cfg, Play.classloader);
        } catch (Exception e) {
            Logger.error(e, "Error trying to override the hibernate classLoader (new hibernate version ???)");
        }
        for (Class<? extends Annotation> clazz : classes) {
            if (clazz.isAnnotationPresent(Entity.class)) {
                cfg.addAnnotatedClass(clazz);
                Logger.trace("JPA Model : %s", clazz);
            }
        }
        String[] moreEntities = Play.configuration.getProperty("jpa.entities", "").split(", ");
        for(String entity : moreEntities) {
            if(entity.trim().equals("")) continue;
            try {
                cfg.addAnnotatedClass(Play.classloader.loadClass(entity));
            } catch(Exception e) {
                Logger.warn("JPA -> Entity not found: %s", entity);
            }
        }
        Logger.trace("Initializing JPA ...");
        EntityManagerFactory entityManagerFactory = cfg.buildEntityManagerFactory();
        return entityManagerFactory.createEntityManager();
    }

}
