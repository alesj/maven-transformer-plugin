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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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

    /**
     * {@inheritDoc}
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Transforming classes: filter = " + filterPattern + ", transformer = " + transformerClassName);

        final TransformerUtils utils = new TransformerUtils();
        utils.setTransformerClassName(transformerClassName);
        utils.setFilterPattern(filterPattern);
        try {
            utils.execute(buildProjectCompileClassLoader(), new Action() {
                public void execute() throws Exception {
                    Build build = project.getBuild();
                    String output = build.getOutputDirectory();
                    File outputDir = new File(output);

                    utils.recurse(outputDir, "");
                }
            });
        } catch (Exception e) {
            throw new MojoExecutionException("Cannot execute transformation.", e);
        }
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
}
