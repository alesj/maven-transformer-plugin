/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.maven.plugins.transformer;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;

import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Transform current classes with ClassFileTransformer instance.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 * @author <a href="mailto:mluksa@redhat.com">Marko Luksa</a>
 */
public class TransformerUtils {
    /**
     * The log
     */
    private static final Logger log = Logger.getLogger(TransformerUtils.class.getName());

    /**
     * The filter.
     * Regexp pattern for matching classes that need to be transformed.
     */
    private String filterPattern;

    /**
     * The transformer class name.
     */
    private String transformerClassName;

    private ClassLoader classLoader;
    private LoaderClassPath loaderClassPath;
    private ClassPool classPool;
    private ClassFileTransformer transformer;

    protected Logger getLog() {
        return log;
    }

    /**
     * Execute transformation from java cmd.
     * <p/>
     * The args are:
     * - jar path
     * - transformer
     * - optional filter
     *
     * @param args the args
     */
    public static void main(String[] args) {
        if (args == null || args.length < 2)
            throw new IllegalArgumentException("Illegal args: " + Arrays.toString(args));

        TransformerUtils tm = new TransformerUtils();
        tm.transformerClassName = args[1];
        tm.filterPattern = (args.length > 2) ? args[2] : null;

        String jar = args[0];
        tm.transformJar(jar);
    }

    protected void transformJar(String jar) {
        final File original = new File(jar);
        if (original.exists() == false)
            throw new IllegalArgumentException("No such jar file: " + jar);

        File parent = original.getParentFile();
        File temp = new File(parent, original.getName() + ".tmp");

        try {
            File copy = new File(parent, "copy-" + original.getName());
            if (copy.exists() && copy.delete() == false)
                throw new IOException("Cannot delete copy jar: " + copy);

            delete(temp);
            if (temp.mkdir() == false)
                throw new IllegalArgumentException("Cannot create temp dir: " + temp);

            JarFile jarFile = new JarFile(original);
            try {
                transformIntoTempDir(original, temp, jarFile);
                createJarFromTempDir(temp, copy, jarFile);
            } finally {
                jarFile.close();
            }

            File old = new File(parent, "old-" + original.getName());
            if (old.exists() && old.delete() == false)
                throw new IOException("Cannot delete old: " + old);
            if (original.renameTo(old) == false)
                throw new IllegalArgumentException("Cannot rename original: " + original + " to old: " + old);
            if (copy.renameTo(original) == false)
                throw new IllegalArgumentException("Cannot rename copy: " + copy + " to actual original: " + original);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void transformIntoTempDir(File original, final File temp, final JarFile jarFile) throws Exception {
        final FileFilter filter = getFilter();
        ClassLoader parentCL = TransformerUtils.class.getClassLoader();
        URLClassLoader cl = new URLClassLoader(new URL[]{original.toURI().toURL()}, parentCL);
        try {
            execute(cl, new Action() {
                public void execute() throws Exception {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        processJarEntry(entries.nextElement());
                    }
                }

                private void processJarEntry(JarEntry entry) throws Exception {
                    String name = entry.getName();
                    if (name.toUpperCase().contains("MANIFEST.MF"))
                        return;

                    File file = new File(temp, name);
                    if (entry.isDirectory()) {
                        file.mkdirs(); // create dirs
                    } else {
                        file.getParentFile().mkdirs(); // make sure we have dirs

                        if (name.endsWith(".class") && (filter == null || filter.accept(file))) {
                            transform(name, file);
                        } else {
                            copy(entry, file);
                        }
                    }
                }

                private void transform(String name, File file) throws Exception {
                    TransformationTarget tt = new TransformationTarget(toClassName(name), file);
                    tt.writeOutChanges();
                }

                private void copy(JarEntry entry, File file) throws IOException {
                    InputStream is = jarFile.getInputStream(entry);
                    FileOutputStream os = new FileOutputStream(file);
                    try {
                        copyStream(is, os);
                    } finally {
                        safeClose(is);
                        safeClose(os);
                    }
                }
            });
        } finally {
            //noinspection ConstantConditions
            if (cl instanceof Closeable) {
                Closeable closeable = (Closeable) cl;
                closeable.close();
            } else {
                log.warning("Cannot close classloader for " + original.getPath());
            }
        }
    }

    private void createJarFromTempDir(File temp, File copy, JarFile jarFile) throws IOException {
        FileOutputStream fos = new FileOutputStream(copy);
        JarOutputStream jos = new JarOutputStream(fos, jarFile.getManifest());
        try {
            for (File f : temp.listFiles())
                writeJar(jos, f, "");
        } finally {
            safeClose(jos);
        }
    }

    protected static void copyStream(final InputStream in, final OutputStream out) throws IOException {
        final byte[] bytes = new byte[8192];
        int cnt;
        while ((cnt = in.read(bytes)) != -1) {
            out.write(bytes, 0, cnt);
        }
    }

    protected static void safeClose(Closeable c) {
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    protected static void delete(File file) throws IOException {
        if (file == null || file.exists() == false)
            return;

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null)
                throw new IOException("Null files, weird I/O error: " + file);

            for (File f : files)
                delete(f);
        }

        if (file.delete() == false)
            throw new IOException("Cannot delete file: " + file);
    }

    protected void writeJar(JarOutputStream jos, File src, String prefix) throws IOException {
        if (src.isDirectory()) {
            // create / init the zip entry
            prefix += (src.getName() + "/");
            ZipEntry entry = new ZipEntry(prefix);
            entry.setTime(src.lastModified());
            entry.setMethod(JarOutputStream.STORED);
            entry.setSize(0L);
            entry.setCrc(0L);
            jos.putNextEntry(entry);
            jos.closeEntry();

            // process the sub-directories
            File[] files = src.listFiles();
            for (File file : files)
                writeJar(jos, file, prefix);
        } else if (src.isFile()) {
            // create / init the zip entry
            ZipEntry entry = new ZipEntry(prefix + src.getName());
            entry.setTime(src.lastModified());
            jos.putNextEntry(entry);
            // dump the file
            FileInputStream in = new FileInputStream(src);
            try {
                copyStream(in, jos);
            } finally {
                safeClose(in);
                jos.closeEntry();
            }
        }
    }

    protected void execute(ClassLoader cl, Action action) throws Exception {
        classLoader = cl;
        loaderClassPath = new LoaderClassPath(classLoader);
        try {
            classPool = new ClassPool(true);
            classPool.appendClassPath(loaderClassPath);

            action.execute();
        } finally {
            loaderClassPath.close();
            classLoader = null;
        }
    }

    protected void recurse(File current, String name) throws Exception {
        recurse(current, getFilter(), name);
    }

    protected void recurse(File current, FileFilter filter, String name) throws Exception {
        if (current.isDirectory()) {
            File[] files = current.listFiles();
            if (files == null)
                throw new IllegalArgumentException("Null files, weird I/O error: " + current);

            if (name.length() > 0)
                name += ".";

            for (File f : files) {
                recurse(f, filter, name + f.getName());
            }
        } else {
            if (name.endsWith(".class") && (filter == null || filter.accept(current))) {
                TransformationTarget tt = new TransformationTarget(toClassName(name));
                tt.writeOutChanges();
            }
        }
    }

    /**
     * Get file filter.
     *
     * @return the file filter
     */
    protected FileFilter getFilter() {
        if (filterPattern == null) {
            return new FileFilter() {
                public boolean accept(File file) {
                    return true;
                }
            };
        } else {
            return new FileFilter() {
                Pattern patter = Pattern.compile(filterPattern);

                public boolean accept(File file) {
                    return patter.matcher(file.getPath()).find();
                }
            };
        }
    }

    /**
     * Get transformer.
     *
     * @return the transformer
     */
    protected ClassFileTransformer getTransformer() {
        if (transformer == null) {
            if (transformerClassName == null)
                throw new IllegalArgumentException("Missing transformer class name!");

            try {
                Class<?> aClass = classLoader.loadClass(transformerClassName);
                transformer = (ClassFileTransformer) aClass.newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return transformer;
    }

    protected static File toFile(String className, URL url) throws Exception {
        if (url == null)
            throw new Exception("No such class name: " + className + ", wrong classloader?");

        try {
            return new File(url.toURI());
        } catch (Exception e) {
            throw new Exception(e.getMessage());
        }
    }

    protected static String toClassName(String fileName) {
        int len = fileName.length() - 6; // 6 ~ .class
        return fileName.replace("/", ".").substring(0, len);
    }

    class TransformationTarget {
        private final File classFileLocation;
        private final CtClass ctClass;

        public TransformationTarget(String className) throws Exception {
            this(className, toFile(className, loaderClassPath.find(className)));
        }

        public TransformationTarget(String className, File file) throws Exception {
            try {
                ctClass = classPool.get(className);
                classFileLocation = file;
            } catch (Throwable e) {
                throw new Exception("Unable to resolve class file path", e);
            }
        }

        public void writeOutChanges() throws Exception {
            getLog().info("writing transformation changes [" + classFileLocation.getAbsolutePath() + "]");
            byte[] original = ctClass.toBytecode();
            byte[] transformed = getTransformer().transform(classLoader, ctClass.getName(), null, null, original);
            if (transformed == null || transformed.length == 0)
                return;

            OutputStream out = new FileOutputStream(classFileLocation);
            try {
                out.write(transformed);
            } finally {
                safeClose(out);
            }

            if (classFileLocation.setLastModified(System.currentTimeMillis()) == false) {
                getLog().info("Unable to manually update class file timestamp: " + classFileLocation);
            }
        }
    }

    public String getFilterPattern() {
        return filterPattern;
    }

    public void setFilterPattern(String filterPattern) {
        this.filterPattern = filterPattern;
    }

    public String getTransformerClassName() {
        return transformerClassName;
    }

    public void setTransformerClassName(String transformerClassName) {
        this.transformerClassName = transformerClassName;
    }
}
