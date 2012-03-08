/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
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
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Transform current classes with ClassFileTransformer instance.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 * @author Steve Ebersole
 * @goal bytecode
 * @phase compile
 * @requiresDependencyResolution
 */
public class TransformerMojo extends AbstractMojo {
    /**
     * The Maven Project Object
     *
     * @parameter expression="${project}"
     * @required
     */
    protected MavenProject project;

    /**
     * The filter.
     * Regexp pattern for matching classes that need to be transformed.
     *
     * @parameter
     */
    protected String filterPattern;

    /**
     * The transformer class name.
     *
     * @parameter
     * @required
     */
    protected String transformerClassName;

    private ClassLoader classLoader;
    private LoaderClassPath loaderClassPath;
    private ClassPool classPool;
    private ClassFileTransformer transformer;

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

        TransformerMojo tm = new TransformerMojo();
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
        final File temp = new File(parent, original.getName() + ".tmp");

        try {
            delete(temp);
            if (temp.mkdir() == false)
                throw new IllegalArgumentException("Cannot create temp dir: " + temp);

            final JarFile jarFile = new JarFile(original);
            final FileFilter filter = getFilter();
            ClassLoader parentCL = TransformerMojo.class.getClassLoader();
            ClassLoader cl = new URLClassLoader(new URL[]{original.toURI().toURL()}, parentCL);

            execute(cl, new Action() {
                public void execute() throws MojoExecutionException {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.toUpperCase().contains("MANIFEST.MF"))
                            continue;

                        File file = new File(temp, name);
                        if (entry.isDirectory()) {
                            file.mkdirs(); // create dirs
                        } else {
                            file.getParentFile().mkdirs(); // make sure we have dirs

                            if (name.endsWith(".class") && (filter == null || filter.accept(file))) {
                                TransformationTarget tt = new TransformationTarget(toClassName(name), file);
                                tt.writeOutChanges();
                            } else // just write down the original
                            {
                                try {
                                    InputStream is = jarFile.getInputStream(entry);
                                    FileOutputStream os = new FileOutputStream(file);
                                    try {
                                        copyStream(is, os);
                                        os.flush();
                                    } finally {
                                        safeClose(is);
                                        safeClose(os);
                                    }
                                } catch (IOException e) {
                                    throw new MojoExecutionException("Cannot write jar entry to original.", e);
                                }
                            }
                        }
                    }
                }
            });

            File copy = new File(parent, "copy-" + original.getName());
            if (copy.exists() && copy.delete() == false)
                throw new IOException("Cannot delete copy jar: " + copy);

            FileOutputStream fos = new FileOutputStream(copy);
            JarOutputStream jos = new JarOutputStream(fos, jarFile.getManifest());
            try {
                for (File f : temp.listFiles())
                    writeJar(jos, f, "");
                jos.flush();
            } finally {
                safeClose(jos);
            }

            File old = new File(parent, "old-" + original.getName());
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

    /**
     * {@inheritDoc}
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Transforming classes: filter = " + filterPattern + ", transformer = " + transformerClassName);

        execute(buildProjectCompileClassLoader(), new Action() {
            public void execute() throws MojoExecutionException {
                FileFilter filter = getFilter();

                Build build = project.getBuild();
                String output = build.getOutputDirectory();
                File outputDir = new File(output);

                recurse(outputDir, filter, "");
            }
        });
    }

    protected void execute(ClassLoader cl, Action action) throws MojoExecutionException, MojoFailureException {
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

    protected void recurse(File current, FileFilter filter, String name) throws MojoExecutionException {
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

    /**
     * Builds a {@link ClassLoader} based on the maven project's compile classpath elements.
     *
     * @return The {@link ClassLoader} made up of the maven project's compile classpath elements.
     * @throws MojoExecutionException Indicates an issue processing one of the classpath elements
     */
    private ClassLoader buildProjectCompileClassLoader() throws MojoExecutionException {
        List<URL> classPathUrls = new ArrayList<URL>();
        for (String path : projectCompileClasspathElements()) {
            try {
                getLog().debug("Adding project compile classpath element : " + path);
                classPathUrls.add(new File(path).toURI().toURL());
            } catch (MalformedURLException e) {
                throw new MojoExecutionException("Unable to build path URL [" + path + "]");
            }
        }
        return new URLClassLoader(classPathUrls.toArray(new URL[classPathUrls.size()]), getClass().getClassLoader());
    }

    /**
     * Essentially a call to {@link MavenProject#getCompileClasspathElements} except that here we
     * cast it to the generic type and internally handle {@link org.apache.maven.artifact.DependencyResolutionRequiredException}.
     *
     * @return The compile classpath elements
     * @throws MojoExecutionException Indicates a {@link org.apache.maven.artifact.DependencyResolutionRequiredException} was encountered
     */
    @SuppressWarnings({"unchecked"})
    private List<String> projectCompileClasspathElements() throws MojoExecutionException {
        try {
            return (List<String>) project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Call to MavenProject#getCompileClasspathElements required dependency resolution");
        }
    }

    private static File toFile(URL url) throws MojoExecutionException {
        if (url == null)
            throw new MojoExecutionException("No such class name: " + url);

        try {
            return new File(url.toURI());
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }

    protected static String toClassName(String fileName) {
        int len = fileName.length() - 6; // 6 ~ .class
        return fileName.replace("/", ".").substring(0, len);
    }

    class TransformationTarget {
        private final File classFileLocation;
        private final CtClass ctClass;

        public TransformationTarget(String className) throws MojoExecutionException {
            this(className, toFile(loaderClassPath.find(className)));
        }

        public TransformationTarget(String className, File file) throws MojoExecutionException {
            try {
                ctClass = classPool.get(className);
                classFileLocation = file;
            } catch (Throwable e) {
                throw new MojoExecutionException("Unable to resolve class file path", e);
            }
        }

        protected void writeOutChanges() throws MojoExecutionException {
            getLog().info("writing transformation changes [" + classFileLocation.getAbsolutePath() + "]");
            try {
                byte[] original = ctClass.toBytecode();
                byte[] transformed = getTransformer().transform(classLoader, ctClass.getName(), null, null, original);
                if (transformed == null || transformed.length == 0)
                    return;

                OutputStream out = new FileOutputStream(classFileLocation);
                try {
                    out.write(transformed);
                    out.flush();
                    if (classFileLocation.setLastModified(System.currentTimeMillis()) == false) {
                        getLog().info("Unable to manually update class file timestamp: " + classFileLocation);
                    }
                } finally {
                    safeClose(out);
                }
            } catch (Exception e) {
                throw new MojoExecutionException("Unable to write out modified class file", e);
            }
        }
    }

    public MavenProject getProject() {
        return project;
    }

    public void setProject(MavenProject project) {
        this.project = project;
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

    private interface Action {
        void execute() throws MojoExecutionException;
    }
}
