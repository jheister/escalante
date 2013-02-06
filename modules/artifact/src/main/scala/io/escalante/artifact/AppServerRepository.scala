/*
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package io.escalante.artifact

import java.io.{FilenameFilter, File}
import maven.MavenArtifact
import org.jboss.as.server.deployment.{Attachments, DeploymentUnit}
import io.escalante.artifact.maven.MavenDependencyResolver
import io.escalante.io.FileSystem._
import io.escalante.xml.ScalaXmlParser._
import org.jboss.as.server.deployment.module.{MountHandle, TempFileProviderService, ResourceRoot, ModuleRootMarker}
import org.jboss.vfs.{VFS, VirtualFile}
import scala.xml.Elem
import io.escalante.Scala
import io.escalante.logging.Log

/**
 * // TODO: Document this
 * @author Galder Zamarreño
 * @since // TODO
 */
class AppServerRepository(root: File) extends ArtifactRepository with Log {

  def installArtifact(
      artifact: MavenArtifact,
      moduleXml: Option[Elem],
      subArtifacts: Seq[MavenArtifact]): JBossModule = {
    // Start by creating a JBoss module out of the Maven artifact
    val module = JBossModule(artifact)
    val moduleDir = new File(root, module.moduleDirName)
    // Check if maven resolution necessary
    if (requiresMavenResolution(moduleDir)) {
      val dir = mkDirs(root, module.moduleDirName)

      // TODO: Parallelize with Scala 2.10 futures...

      // Take all artifacts, both main artifact and sub-artifact,
      // and create a single list will all the jar files
      val jarFiles = (artifact :: subArtifacts.toList).flatMap(artifact =>
        MavenDependencyResolver.resolveArtifact(artifact))

      jarFiles.foreach(jar => copy(jar, new File(dir, jar.getName)))

      val descriptor = moduleXml.getOrElse {
        val templateModuleXml =
          <module xmlns="urn:jboss:module:1.1" name={module.name}
                  slot={module.slot}>
            <resources/>
            <dependencies/>
          </module>

        val resourceRoots = jarFiles.map {
          jar => <resource-root path={jar.getName}/>
        }

        addXmlElements("resources", resourceRoots, templateModuleXml)
      }

      saveXml(new File(dir, "module.xml"), descriptor)
    }

    module
  }

  def attachArtifacts(
      artifacts: Seq[MavenArtifact],
      deployment: DeploymentUnit,
      mountPoint: String) {
    // TODO: Parallelize with Scala 2.10 futures...
    // Flat map so that each maven dependencies files are then combined into
    // a single sequence of files to add to deployment unit
    val jars = artifacts.flatMap { artifact =>
      MavenDependencyResolver.resolveArtifact(artifact)
      // TODO: add more clever logic to resolveArtifact:
      //  - if lift 2.4 + scala 2.9.2 does not exist, check is scala version is latest
      //  - if it is, try "decreasing version", so "2.9.1"... that way all the way down
      //  - if it's not latest, try latest and then others
    }.distinct // Remove duplicates to avoid duplicate mount errors

    val resourceRoot = deployment.getAttachment(Attachments.DEPLOYMENT_ROOT)
    val root = resourceRoot.getRoot

    jars.foreach {
      jar =>
        val temp = root.getChild(mountPoint) // Virtual Lift mount point
        val repackagedJar = createZipRoot(temp, jar)
        debug("Attaching %s to %s", jar, mountPoint)
        ModuleRootMarker.mark(repackagedJar)
        deployment.addToAttachmentList(
          Attachments.RESOURCE_ROOTS, repackagedJar)
    }
  }

  /**
   * Creates a Zip root under the virtual mount point to store the file.
   */
  private def createZipRoot(
      deploymentTemp: VirtualFile,
      file: File): ResourceRoot = {
    val archive = deploymentTemp.getChild(file.getName)
    val closable = VFS.mountZip(
        file, archive, TempFileProviderService.provider())
    new ResourceRoot(file.getName, archive, new MountHandle(closable))
  }

  /**
   * TODO
   *
   * @param moduleDir
   * @return
   */
  private def requiresMavenResolution(moduleDir: File): Boolean = {
    val jarFiles = moduleDir.list(new FilenameFilter {
      def accept(dir: File, name: String): Boolean =
        name.endsWith(".jar")
    })

    !moduleDir.exists() || jarFiles.isEmpty
  }

}
