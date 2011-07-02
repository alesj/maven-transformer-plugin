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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

/**
 * Transform current classes with ClassFileTransformer instance.
 *
 * @goal bytecode
 * @phase compile
 * @requiresDependencyResolution
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 * @author Steve Ebersole
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
    * @required
    */
   protected String filter;

   private ClassLoader classLoader;
   private LoaderClassPath loaderClassPath;
   private ClassPool classPool;
   private ClassFileTransformer transformer;

   /**
    * {@inheritDoc}
    */
   public void execute() throws MojoExecutionException, MojoFailureException
   {
      classLoader = buildProjectCompileClassLoader();
      loaderClassPath = new LoaderClassPath(classLoader);

      try
      {
         transformer = getTransformer();

         classPool = new ClassPool(true);
         classPool.appendClassPath(loaderClassPath);

         // TODO
      }
      finally
      {
         loaderClassPath.close();
         classLoader = null;
      }
   }

   /**
    * Get transformer.
    *
    * @return the transformer
    */
   protected ClassFileTransformer getTransformer()
   {
      return null;
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

   class TransformationTarget
   {
      private final File classFileLocation;
      private final CtClass ctClass;

      public TransformationTarget(String className) throws MojoExecutionException
      {
         try
         {
            classFileLocation = new File(loaderClassPath.find(className).toURI());
            ctClass = classPool.get(className);
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
               byte[] transformed = transformer.transform(classLoader, ctClass.getName(), null, null, original);
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
}
