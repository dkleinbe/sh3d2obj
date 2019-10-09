/*
 * HomeXMLFileRecorder.java
 *
 * Copyright (c) 2016 Emmanuel PUYBARET / eTeks <info@eteks.com>. All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.eteks.sweethome3d.plugin.exportxml;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import javax.media.j3d.BranchGroup;
import javax.media.j3d.Node;

import com.eteks.sweethome3d.io.ContentRecording;
import com.eteks.sweethome3d.io.DefaultHomeInputStream;
import com.eteks.sweethome3d.io.DefaultHomeOutputStream;
import com.eteks.sweethome3d.io.XMLWriter;
import com.eteks.sweethome3d.j3d.Ground3D;
import com.eteks.sweethome3d.j3d.OBJWriter;
import com.eteks.sweethome3d.j3d.Object3DBranchFactory;
import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeRecorder;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.InterruptedRecorderException;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.RecorderException;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.model.UserPreferences;
import com.eteks.sweethome3d.tools.OperatingSystem;
import com.eteks.sweethome3d.tools.URLContent;
import com.eteks.sweethome3d.viewcontroller.Object3DFactory;

public class HomeXMLFileRecorder implements HomeRecorder {
  public static final int INCLUDE_VIEWER_DATA          = 0x0001;
  public static final int INCLUDE_HOME_STRUCTURE       = 0x0002;
  public static final int INCLUDE_ICONS                = 0x0004;
  public static final int CONVERT_MODELS_TO_OBJ_FORMAT = 0x0008;
  public static final int REDUCE_IMAGES                = 0x0010;

  private int compressionLevel;
  private int flags;
  private int imageMaxPreferredSize;

  public HomeXMLFileRecorder(int compressionLevel, int flags) {
    this(compressionLevel, flags, 256);
  }

  public HomeXMLFileRecorder(int compressionLevel, int flags, int imageMaxPreferredSize) {
    this.compressionLevel = compressionLevel;
    this.flags = flags;
    this.imageMaxPreferredSize = imageMaxPreferredSize;
  }

  public void writeHome(Home home, String exportedFileName) throws RecorderException {
    File homeFile = null;
    try {
      // Save home in a temporary file to ensure all items are gathered
      homeFile = OperatingSystem.createTemporaryFile("Home", ".sh3d");
      DefaultHomeOutputStream out = new DefaultHomeOutputStream(new FileOutputStream(homeFile), 0, false);
      out.writeHome(home);
      out.close();

      exportHome(homeFile, new File(exportedFileName), null);
    } catch (InterruptedIOException ex) {
      throw new InterruptedRecorderException("Save home to XML");
    } catch (IOException ex) {
      throw new RecorderException("Couldn't save home to XML", ex);
    } finally {
      if (homeFile != null) {
        homeFile.delete();
      }
    }
  }

  public void exportHome(File homeFile, File exportedFile, UserPreferences preferences) throws RecorderException {
    DefaultHomeInputStream in = null;
    Home home;
    try {
      // If preferences are not null replace home content by the one in preferences when it's the same
      in = new DefaultHomeInputStream(homeFile,
          ContentRecording.INCLUDE_ALL_CONTENT, null, preferences, preferences != null);
      home = in.readHome();
    } catch (InterruptedIOException ex) {
      throw new InterruptedRecorderException("Save home to XML");
    } catch (IOException ex) {
      throw new RecorderException("Couldn't read exported home to XML", ex);
    } catch (ClassNotFoundException ex) {
      // Shouldn't happen
      throw new RecorderException("Couldn't read exported home to XML", ex);
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException ex) {
          ex.printStackTrace();
        }
      }
    }

    File homeStructureFile = null;
    ZipOutputStream zipOut = null;
    try {
      String homeStructure;
      if ((this.flags & INCLUDE_HOME_STRUCTURE) != 0) {
        // Export home structure in a zipped OBJ file
        homeStructure = "HomeStructure/Home.obj";
        homeStructureFile = exportHomeStructure(home, new Object3DBranchFactory(),
            homeStructure.substring(homeStructure.lastIndexOf('/') + 1));
      } else {
        homeStructure = null;
      }

      zipOut = new ZipOutputStream(new FileOutputStream(exportedFile));
      zipOut.setLevel(this.compressionLevel);
      // Export home to XML
      zipOut.putNextEntry(new ZipEntry("Home.xml"));
      XMLWriter writer = new XMLWriter(zipOut);
      String homeName = null;
      if (home.getName() != null) {
        homeName = new File(home.getName()).getName();
      }
      Set<Content> referencedContents = writeHomeToXML(writer, home, homeName, homeStructure, this.flags);
      writer.flush();
      zipOut.closeEntry();

      if ((this.flags & INCLUDE_HOME_STRUCTURE) != 0) {
        // Save Home.obj structure and its dependencies in HomeStructure directory
        writeAllZipEntries(zipOut, homeStructure.substring(0, homeStructure.lastIndexOf('/')), homeStructureFile.toURI().toURL(), this.flags);
      }
      // Save content referenced by home XML entry taking into account export flags
      List<String> homeFileEntries = new ArrayList<String>();
      for (Content content : referencedContents) {
        if (content instanceof RedirectedURLContent) {
          String directoryName = ((RedirectedURLContent)content).getJAREntryName();
          directoryName = directoryName.substring(0, directoryName.indexOf('/'));
          writeAllZipEntries(zipOut, directoryName, ((RedirectedURLContent)content).getTargetContent().getJAREntryURL(), this.flags);
        } else if (content instanceof URLContent) {
          HomeTexture skyTexture = home.getEnvironment().getSkyTexture();
          if (skyTexture != null && skyTexture.getImage().equals(content)) {
            // Reduce less sky texture image
            writeContentZipEntries(zipOut, (URLContent)content, homeFileEntries, this.flags, this.imageMaxPreferredSize * 4);
          } else {
            writeContentZipEntries(zipOut, (URLContent)content, homeFileEntries, this.flags, this.imageMaxPreferredSize);
          }
        }
      }
      zipOut.finish();
    } catch (InterruptedIOException ex) {
      throw new InterruptedRecorderException("Save home to XML");
    } catch (IOException ex) {
      throw new RecorderException("Couldn't save home to XML", ex);
    } finally {
      if (homeStructureFile != null) {
        homeStructureFile.delete();
      }

      if (zipOut != null) {
        try {
          zipOut.close();
        } catch (IOException ex) {
          throw new RecorderException("Couldn't close home file", ex);
        }
      }
    }
  }

  /**
   * Writes the given <code>home</code> in XML and returns the content that is required by this home.
   */
  protected Set<Content> writeHomeToXML(XMLWriter writer, Home home, String homeName, String homeStructure, int flags) throws IOException {
    HomeXMLOptionalExporter homeExporter = new HomeXMLOptionalExporter(home, homeName, homeStructure, flags);
    homeExporter.writeElement(writer, home);
    return homeExporter.getReferencedContents();
  }

  /**
   * Exports the structure of the given <code>home</code> at OBJ format
   * and returns the temporary zip file where it's stored.
   */
  private File exportHomeStructure(Home home, Object3DFactory objectFactory,
                                   String homeStructureObjName) throws IOException {
    // Clone home to be able to handle it independently
    home = home.clone();
    List<Level> levels = home.getLevels();
    for (int i = 0; i < levels.size(); i++) {
      if (levels.get(i).isViewable()) {
        levels.get(i).setVisible(true);
      }
    }

    BranchGroup root = new BranchGroup();
    // Add 3D ground, walls, rooms and labels
    root.addChild(new Ground3D(home, -0.5E5f, -0.5E5f, 1E5f, 1E5f, true));
    for (Selectable item : home.getSelectableViewableItems()) {
      if (!(item instanceof HomePieceOfFurniture)) {
        root.addChild((Node)objectFactory.createObject3D(home, item, true));
      }
    }
    File tempZipFile = OperatingSystem.createTemporaryFile("HomeStructure", ".zip");
    OBJWriter.writeNodeInZIPFile(root, tempZipFile, 0, homeStructureObjName, "Home structure for HTML5 export");
    return tempZipFile;
  }

  /**
   * Writes in <code>zipOut</code> stream one or more entries matching the content
   * <code>content</code> coming from a home file.
   */
  private void writeContentZipEntries(ZipOutputStream zipOut, URLContent urlContent,
                                      List<String> homeFileEntries, int exportFlags, final int imageMaxSize) throws IOException {
    String entryName = urlContent.getJAREntryName();
    int slashIndex = entryName.indexOf('/');
    // If content comes from a directory of a home file
    if (slashIndex > 0) {
      URL zipUrl = urlContent.getJAREntryURL();
      String entryDirectory = entryName.substring(0, slashIndex + 1);
      // Write in home stream each zipped stream entry that is stored in the same directory
      for (String zipEntryName : getZipUrlEntries(zipUrl, homeFileEntries)) {
        if (zipEntryName.startsWith(entryDirectory)) {
          URLContent siblingContent = new URLContent(new URL("jar:" + zipUrl + "!/"
              + URLEncoder.encode(zipEntryName, "UTF-8").replace("+", "%20")));
          writeZipEntry(zipOut, entryDirectory + zipEntryName.substring(slashIndex + 1), siblingContent, exportFlags, imageMaxSize);
        }
      }
    } else {
      writeZipEntry(zipOut, entryName, urlContent, exportFlags, imageMaxSize);
    }
  }

  /**
   * Writes in <code>zipOut</code> stream all the entries of the zipped <code>urlContent</code>.
   */
  private void writeAllZipEntries(ZipOutputStream zipOut,
                                  String directory,
                                  URL url, int exportFlags) throws IOException {
    ZipInputStream zipIn = null;
    try {
      // Open zipped stream that contains urlContent
      zipIn = new ZipInputStream(url.openStream());
      // Write each zipped stream entry in zip stream
      for (ZipEntry entry; (entry = zipIn.getNextEntry()) != null; ) {
        String zipEntryName = entry.getName();
        URLContent siblingContent = new URLContent(new URL("jar:" + url + "!/"
            + URLEncoder.encode(zipEntryName, "UTF-8").replace("+", "%20")));
        writeZipEntry(zipOut, directory + "/" + zipEntryName, siblingContent, exportFlags, this.imageMaxPreferredSize);
      }
    } finally {
      if (zipIn != null) {
        zipIn.close();
      }
    }
  }

  /**
   * Writes in <code>zipOut</code> stream a new entry named <code>entryName</code> that
   * contains a given <code>content</code>.
   */
  private void writeZipEntry(ZipOutputStream zipOut, String entryName,
                             URLContent content, int exportFlags, final int imageMaxSize) throws IOException {
    byte [] buffer = new byte [8192];
    InputStream contentIn = null;
    try {
      zipOut.putNextEntry(new ZipEntry(entryName));
      if ((exportFlags & REDUCE_IMAGES) != 0
          && content.isJAREntry()
          && (content.getJAREntryName().endsWith(".jpg")
              || content.getJAREntryName().endsWith(".png")
              || content.getJAREntryName().indexOf(".") == -1)) {
        // Get content
        ByteArrayOutputStream contentOut = new ByteArrayOutputStream();
        contentIn = content.openStream();
        for (int size; (size = contentIn.read(buffer)) != -1; ) {
          contentOut.write(buffer, 0, size);
        }
        contentIn.close();
        byte [] imageBytes = contentOut.toByteArray();

        contentIn = new ByteArrayInputStream(imageBytes);
        ImageInputStream imageIn = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes));
        for (Iterator<ImageReader> it = ImageIO.getImageReaders(imageIn);
            it.hasNext(); ) {
          ImageReader reader = (ImageReader)it.next();
          if (reader != null) {
            reader.setInput(imageIn);
            int minIndex = reader.getMinIndex();
            ImageTypeSpecifier rawImageType = reader.getRawImageType(minIndex);
            boolean opaqueImage = rawImageType == null || rawImageType.getColorModel().getTransparency() == Transparency.OPAQUE;
            // If image is larger than the max size or if it's a small opaque image not at JPEG format
            // (nothing to spare for JPEG small images and too much quality loss for small transparent images)
            if (reader.getWidth(minIndex) > imageMaxSize
                || reader.getHeight(minIndex) > imageMaxSize
                || !"JPEG".equalsIgnoreCase(reader.getFormatName())
                   && opaqueImage) {
              // Compute reduced opaque image
              BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
              BufferedImage reducedImage = new BufferedImage(
                  Math.min(image.getWidth(), imageMaxSize),  Math.min(image.getHeight(), imageMaxSize),
                  opaqueImage // Avoid image.getType() otherwise color profile of PNG images won't work when saved at JPEG format
                      ? BufferedImage.TYPE_INT_RGB
                      : BufferedImage.TYPE_INT_ARGB);
              Graphics2D g2D = (Graphics2D)reducedImage.getGraphics();
              g2D.drawImage(image.getScaledInstance(reducedImage.getWidth(), reducedImage.getHeight(), Image.SCALE_SMOOTH), 0, 0, null);
              g2D.dispose();
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              // Keep a favor for PNG for model textures and non opaque images
              ImageIO.write(reducedImage, content.getURL().toString().endsWith(".png") || !opaqueImage ? "PNG" : "JPEG", out);
              byte [] reducedImageBytes = out.toByteArray();
              // Use reduced image if it's 80% smaller
              if (reducedImageBytes.length < 0.8f * imageBytes.length) {
                contentIn = new ByteArrayInputStream(reducedImageBytes);
              }
            }
            // Stop iteration among readers
            break;
          }
        }
      } else {
        contentIn = content.openStream();
      }

      // Write content
      int size;
      while ((size = contentIn.read(buffer)) != -1) {
        zipOut.write(buffer, 0, size);
      }
      zipOut.closeEntry();
    } finally {
      if (contentIn != null) {
        contentIn.close();
      }
    }
  }

  /**
   * Returns the list of entries contained in <code>zipUrl</code>.
   */
  private List<String> getZipUrlEntries(URL zipUrl, List<String> zipUrlEntries) throws IOException {
    if (zipUrlEntries.isEmpty()) {
      ZipInputStream zipIn = null;
      try {
        // Search all entries of zip url
        zipIn = new ZipInputStream(zipUrl.openStream());
        for (ZipEntry entry; (entry = zipIn.getNextEntry()) != null; ) {
          zipUrlEntries.add(entry.getName());
        }
      } finally {
        if (zipIn != null) {
          zipIn.close();
        }
      }
    }
    return zipUrlEntries;
  }

  /**
   * Not supported.
   */
  public Home readHome(String name) throws RecorderException {
    throw new UnsupportedOperationException("Unable to read XML files");
  }

  public boolean exists(String name) throws RecorderException {
    return new File(name).exists();
  }
}