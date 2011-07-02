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

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

/**
 * Transform current classes with ClassFileTransformer instance.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 * @author Steve Ebersole
 * @goal bytecode
 * @phase compile
 * @requiresDependencyResolution
 */
public class TransformerMojo extends AbstractMojo
{
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
    *
    * The args are:
    * - jar path
    * - transformer
    * - optional filter
    *
    * @param args the args
    */
   public static void main(String[] args)
   {
      if (args == null || args.length < 2)
         throw new IllegalArgumentException("Illegal args: " + Arrays.toString(args));

      TransformerMojo tm = new TransformerMojo();
      tm.transformerClassName = args[1];
      tm.filterPattern = (args.length > 2) ? args[2] : null;

      String jar = args[0];
      tm.transformJar(jar);
   }

   protected void transformJar(String jar)
   {
      File file = new File(jar);
      if (file.exists() == false)
         throw new IllegalArgumentException("No such jar file: " + jar);

      File parent = file.getParentFile();
      final File temp = new File(parent, file.getName() + ".tmp");
      if (temp.exists())
      {
         getLog().info("Temp dir already exists, potential old code present: " + temp);
      }
      else
      {
         if (temp.mkdir() == false)
            throw new IllegalArgumentException("Cannot create temp dir: " + temp);
      }

      try
      {
         final JarFile jarFile = new JarFile(file);
         final FileFilter filter = getFilter();

         execute(TransformerMojo.class.getClassLoader(), new Action()
         {
            public void execute() throws MojoExecutionException
            {
               Enumeration<JarEntry> entries = jarFile.entries();
               while (entries.hasMoreElements())
               {
                  JarEntry entry = entries.nextElement();
                  String name = entry.getName();
                  File file = new File(temp, name);
                  if (entry.isDirectory())
                  {
                     file.mkdirs(); // create dirs
                  }
                  else if (filter == null || filter.accept(file))
                  {
                     file.getParentFile().mkdirs(); // make sure we have dirs

                     String className = name.replace("/", ".");
                     TransformationTarget tt = new TransformationTarget(className, file);
                     tt.writeOutChanges();
                  }
               }
            }
         });
      }
      catch (Exception e)
      {
         throw new RuntimeException(e);
      }
   }

   /**
    * {@inheritDoc}
    */
   public void execute() throws MojoExecutionException, MojoFailureException
   {
      execute(buildProjectCompileClassLoader(), new Action()
      {
         public void execute() throws MojoExecutionException
         {
            FileFilter filter = getFilter();

            Build build = project.getBuild();
            String output = build.getOutputDirectory();
            File outputDir = new File(output);

            recurse(outputDir, filter, "");
         }
      });
   }

   protected void execute(ClassLoader cl, Action action) throws MojoExecutionException, MojoFailureException
   {
      classLoader = cl;
      loaderClassPath = new LoaderClassPath(classLoader);
      try
      {
         classPool = new ClassPool(true);
         classPool.appendClassPath(loaderClassPath);

         action.execute();
      }
      finally
      {
         loaderClassPath.close();
         classLoader = null;
      }
   }

   protected void recurse(File current, FileFilter filter, String name) throws MojoExecutionException
   {
      if (current.isDirectory())
      {
         File[] files = current.listFiles(filter);
         if (files == null)
            throw new IllegalArgumentException("Null files, weird I/O error: " + current);

         if (name.length() > 0)
            name += ".";

         for (File f : files)
         {
            recurse(f, filter, name + f.getName());
         }
      }
      else
      {
         if (name.endsWith(".class"))
         {
            TransformationTarget tt = new TransformationTarget(name);
            tt.writeOutChanges();
         }
      }
   }

   /**
    * Get file filter.
    *
    * @return the file filter
    */
   protected FileFilter getFilter()
   {
      if (filterPattern == null)
      {
         return new FileFilter()
         {
            public boolean accept(File file)
            {
               return true;
            }
         };
      }
      else
      {
         return new FileFilter()
         {
            Pattern patter = Pattern.compile(filterPattern);

            public boolean accept(File file)
            {
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
   protected ClassFileTransformer getTransformer()
   {
      if (transformer == null)
      {
         if (transformerClassName == null)
            throw new IllegalArgumentException("Missing transformer class name!");

         try
         {
            Class<?> aClass = classLoader.loadClass(transformerClassName);
            transformer = (ClassFileTransformer) aClass.newInstance();
         }
         catch (Exception e)
         {
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
   private ClassLoader buildProjectCompileClassLoader() throws MojoExecutionException
   {
      List<URL> classPathUrls = new ArrayList<URL>();
      for (String path : projectCompileClasspathElements())
      {
         try
         {
            getLog().debug("Adding project compile classpath element : " + path);
            classPathUrls.add(new File(path).toURI().toURL());
         }
         catch (MalformedURLException e)
         {
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
   private List<String> projectCompileClasspathElements() throws MojoExecutionException
   {
      try
      {
         return (List<String>) project.getCompileClasspathElements();
      }
      catch (DependencyResolutionRequiredException e)
      {
         throw new MojoExecutionException("Call to MavenProject#getCompileClasspathElements required dependency resolution");
      }
   }

   private static File toFile(URL url) throws MojoExecutionException
   {
      if (url == null)
         throw new MojoExecutionException("No such class name: " + url);

      try
      {
         return new File(url.toURI());
      }
      catch (Exception e)
      {
         throw new MojoExecutionException(e.getMessage());
      }
   }

   class TransformationTarget
   {
      private final File classFileLocation;
      private final CtClass ctClass;

      public TransformationTarget(String className) throws MojoExecutionException
      {
         this(className, toFile(loaderClassPath.find(className)));
      }

      public TransformationTarget(String className, File file) throws MojoExecutionException
      {
         try
         {
            ctClass = classPool.get(className);
            classFileLocation = file;
         }
         catch (Throwable e)
         {
            throw new MojoExecutionException("Unable to resolve class file path", e);
         }
      }

      protected void writeOutChanges() throws MojoExecutionException
      {
         getLog().info("writing injection changes back [" + classFileLocation.getAbsolutePath() + "]");
         try
         {
            OutputStream out = new FileOutputStream(classFileLocation);
            try
            {
               byte[] original = ctClass.toBytecode();
               byte[] transformed = getTransformer().transform(classLoader, ctClass.getName(), null, null, original);
               out.write(transformed);
               out.flush();
               if (classFileLocation.setLastModified(System.currentTimeMillis()) == false)
               {
                  getLog().info("Unable to manually update class file timestamp");
               }
            }
            finally
            {
               out.close();
            }
         }
         catch (Exception e)
         {
            throw new MojoExecutionException("Unable to write out modified class file", e);
         }
      }
   }

   public MavenProject getProject()
   {
      return project;
   }

   public void setProject(MavenProject project)
   {
      this.project = project;
   }

   public String getFilterPattern()
   {
      return filterPattern;
   }

   public void setFilterPattern(String filterPattern)
   {
      this.filterPattern = filterPattern;
   }

   public String getTransformerClassName()
   {
      return transformerClassName;
   }

   public void setTransformerClassName(String transformerClassName)
   {
      this.transformerClassName = transformerClassName;
   }

   private interface Action
   {
      void execute() throws MojoExecutionException;
   }
}
