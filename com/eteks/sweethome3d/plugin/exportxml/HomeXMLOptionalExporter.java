/*
 * HomeXMLOptionalExporter.java
 *
 * Copyright (c) 2015 Emmanuel PUYBARET / eTeks <info@eteks.com>. All Rights Reserved.
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

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.media.j3d.Appearance;
import javax.media.j3d.BranchGroup;

import com.eteks.sweethome3d.io.HomeXMLExporter;
import com.eteks.sweethome3d.io.ObjectXMLExporter;
import com.eteks.sweethome3d.io.XMLWriter;
import com.eteks.sweethome3d.j3d.ModelManager;
import com.eteks.sweethome3d.j3d.OBJWriter;
import com.eteks.sweethome3d.model.BackgroundImage;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.DimensionLine;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeDoorOrWindow;
import com.eteks.sweethome3d.model.HomeEnvironment;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomeMaterial;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Label;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Polyline;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;
import com.eteks.sweethome3d.tools.OperatingSystem;
import com.eteks.sweethome3d.tools.ResourceURLContent;
import com.eteks.sweethome3d.tools.TemporaryURLContent;
import com.eteks.sweethome3d.tools.URLContent;

/**
 * Exporter for home instances with optional flags.
 */
public class HomeXMLOptionalExporter extends HomeXMLExporter {
  private String                            homeName;
  private String                            homeStructure;
  private int                               flags;
  private HashSet<Content>                  referencedContents;
  private Map<Content, Map<String, String>> contentMaterialUpdatedKeys;

  public HomeXMLOptionalExporter(Home home, String homeName, String homeStructure, int flags) {
    this.homeName = homeName;
    this.homeStructure = homeStructure;
    this.flags = flags;
    this.referencedContents = new HashSet<Content>();
    this.contentMaterialUpdatedKeys = new HashMap<Content, Map<String,String>>();
  }

  protected int getFlags() {
    return this.flags;
  }

  public HashSet<Content> getReferencedContents() {
    return this.referencedContents;
  }

  @Override
  protected void writeAttributes(XMLWriter writer, Home home) throws IOException {
    writer.writeAttribute("version", String.valueOf(home.getVersion()));
    if (getFlags() != 0) {
      writer.writeAttribute("exportFlags", String.valueOf(getFlags()));
    }
    if (home.getName() != null) {
      writer.writeAttribute("name", this.homeName);
    }
    if (this.homeStructure != null) {
      writer.writeAttribute("structure", this.homeStructure, null);
    }
    writer.writeAttribute("camera", home.getCamera() == home.getObserverCamera() ? "observerCamera" : "topCamera");
    writer.writeAttribute("selectedLevel", getId(home.getSelectedLevel()), null);
    writer.writeFloatAttribute("wallHeight", home.getWallHeight());
    if ((getFlags() & HomeXMLFileRecorder.INCLUDE_VIEWER_DATA) == 0) {
      writer.writeBooleanAttribute("basePlanLocked", home.isBasePlanLocked(), false);
      if (home.getFurnitureSortedProperty() != null) {
        writer.writeAttribute("furnitureSortedProperty", home.getFurnitureSortedProperty().name());
      }
      writer.writeBooleanAttribute("furnitureDescendingSorted", home.isFurnitureDescendingSorted(), false);
    }
  }

  @Override
  protected void writeChildren(XMLWriter writer, Home home) throws IOException {
    if ((getFlags() & HomeXMLFileRecorder.INCLUDE_VIEWER_DATA) == 0) {
      super.writeChildren(writer, home);
    } else {
      // Export properties
      List<String> propertiesNames = new ArrayList<String>(home.getPropertyNames());
      Collections.sort(propertiesNames);
      for (String propertyName : propertiesNames) {
        if (!propertyName.startsWith("com.eteks.sweethome3d.")) {
          String propertyValue = home.getProperty(propertyName);
          if (propertyValue  != null) {
            writer.writeStartElement("property");
            writer.writeAttribute("name", propertyName);
            writer.writeAttribute("value", propertyValue);
            writer.writeEndElement();
          }
        }
      }

      // Export environment and cameras
      writeEnvironment(writer, home.getEnvironment());
      writeCamera(writer, home.getObserverCamera(), "observerCamera");
      writeCamera(writer, home.getTopCamera(), "topCamera");
      for (Camera camera : home.getStoredCameras()) {
        writeCamera(writer, camera, "storedCamera");
      }
      // Write level elements
      for (Level level : home.getLevels()) {
        writeLevel(writer, level);
      }
      // Write furniture elements
      for (HomePieceOfFurniture piece : home.getFurniture()) {
        writePieceOfFurniture(writer, piece);
      }
      for (Wall wall : home.getWalls()) {
        writeWall(writer, wall);
      }
      for (Room room : home.getRooms()) {
        writeRoom(writer, room);
      }
      for (Label label : home.getLabels()) {
        if (label.getPitch() != null) {
          writeLabel(writer, label);
        }
      }
    }
  }

  protected void writeEnvironment(XMLWriter writer, HomeEnvironment environment) throws IOException {
    if ((getFlags() & HomeXMLFileRecorder.INCLUDE_VIEWER_DATA) == 0) {
      super.writeEnvironment(writer, environment);
    } else {
      new ObjectXMLExporter<HomeEnvironment>() {
          @Override
          protected void writeAttributes(XMLWriter writer, HomeEnvironment environment) throws IOException {
            writer.writeColorAttribute("groundColor", environment.getGroundColor());
            writer.writeColorAttribute("skyColor", environment.getSkyColor());
            writer.writeColorAttribute("lightColor", environment.getLightColor());
            writer.writeFloatAttribute("wallsAlpha", environment.getWallsAlpha(), 0);
            writer.writeBooleanAttribute("allLevelsVisible", environment.isAllLevelsVisible(), false);
            writer.writeBooleanAttribute("observerCameraElevationAdjusted", environment.isObserverCameraElevationAdjusted(), true);
          }

          @Override
          protected void writeChildren(XMLWriter writer, HomeEnvironment environment) throws IOException {
            if (!environment.getVideoCameraPath().isEmpty()) {
              for (Camera camera : environment.getVideoCameraPath()) {
                writeCamera(writer, camera, "cameraPath");
              }
            }
            writeTexture(writer, environment.getGroundTexture(), "groundTexture");
            writeTexture(writer, environment.getSkyTexture(), "skyTexture");
          }
        }.writeElement(writer, environment);
    }
  }

  protected void writeBackgroundImage(XMLWriter writer, BackgroundImage backgroundImage) throws IOException {
    if ((getFlags() & HomeXMLFileRecorder.INCLUDE_VIEWER_DATA) == 0) {
      super.writeBackgroundImage(writer, backgroundImage);
    }
  }

  protected void writePieceOfFurniture(XMLWriter writer, HomePieceOfFurniture piece) throws IOException {
    if ((getFlags() & HomeXMLFileRecorder.INCLUDE_VIEWER_DATA) == 0
        || piece.isVisible()) {
      new PieceOfFurnitureOptionalExporter().writeElement(writer, piece);
    }
  }

  protected class PieceOfFurnitureOptionalExporter extends PieceOfFurnitureExporter {
    @Override
    protected void writeAttributes(XMLWriter writer, HomePieceOfFurniture piece) throws IOException {
      if (piece.getLevel() != null) {
        writer.writeAttribute("level", getId(piece.getLevel()));
      }
      writer.writeAttribute("catalogId", piece.getCatalogId(), null);
      writer.writeAttribute("name", piece.getName());
      writer.writeAttribute("creator", piece.getCreator(), null);
      writer.writeAttribute("model", getModelExportedContentName(piece, piece.getModel()), null);
      if ((getFlags() & HomeXMLFileRecorder.INCLUDE_VIEWER_DATA) == 0
          || (getFlags() & HomeXMLFileRecorder.INCLUDE_ICONS) != 0) {
        writer.writeAttribute("icon", getExportedContentName(piece, piece.getIcon()), null);
        writer.writeAttribute("planIcon", getExportedContentName(piece, piece.getPlanIcon()), null);
      }
      writer.writeFloatAttribute("x", piece.getX());
      writer.writeFloatAttribute("y", piece.getY());
      writer.writeFloatAttribute("elevation", piece.getElevation(), 0f);
      writer.writeFloatAttribute("angle", piece.getAngle(), 0f);
      writer.writeFloatAttribute("pitch", piece.getPitch(), 0f);
      writer.writeFloatAttribute("roll", piece.getRoll(), 0f);
      writer.writeFloatAttribute("width", piece.getWidth());
      writer.writeFloatAttribute("widthInPlan", piece.getWidthInPlan(), piece.getWidth());
      writer.writeFloatAttribute("depth", piece.getDepth());
      writer.writeFloatAttribute("depthInPlan", piece.getDepthInPlan(), piece.getDepth());
      writer.writeFloatAttribute("height", piece.getHeight());
      writer.writeFloatAttribute("heightInPlan", piece.getHeightInPlan(), piece.getHeight());
      writer.writeBooleanAttribute("backFaceShown", piece.isBackFaceShown(), false);
      writer.writeBooleanAttribute("modelMirrored", piece.isModelMirrored(), false);
      writer.writeBooleanAttribute("visible", piece.isVisible(), true);
      writer.writeColorAttribute("color", piece.getColor());
      if (piece.getShininess() != null) {
        writer.writeFloatAttribute("shininess", piece.getShininess());
      }
      float [][] modelRotation = piece.getModelRotation();
      String modelRotationString =
          floatToString(modelRotation[0][0]) + " " + floatToString(modelRotation[0][1]) + " " + floatToString(modelRotation[0][2]) + " "
        + floatToString(modelRotation[1][0]) + " " + floatToString(modelRotation[1][1]) + " " + floatToString(modelRotation[1][2]) + " "
        + floatToString(modelRotation[2][0]) + " " + floatToString(modelRotation[2][1]) + " " + floatToString(modelRotation[2][2]);
      writer.writeAttribute("modelRotation", modelRotationString, "1 0 0 0 1 0 0 0 1");
      writer.writeBooleanAttribute("modelCenteredAtOrigin", piece.isModelCenteredAtOrigin(), true);
      writer.writeLongAttribute("modelSize", piece.getModelSize());
      writer.writeAttribute("description", piece.getDescription(), null);
      writer.writeAttribute("information", piece.getInformation(), null);
      writer.writeBooleanAttribute("movable", piece.isMovable(), true);
      if (!(piece instanceof HomeFurnitureGroup)) {
        if (!(piece instanceof HomeDoorOrWindow)) {
          writer.writeBooleanAttribute("doorOrWindow", piece.isDoorOrWindow(), false);
          writer.writeBooleanAttribute("horizontallyRotatable", piece.isHorizontallyRotatable(), true);
        }
        writer.writeBooleanAttribute("resizable", piece.isResizable(), true);
        writer.writeBooleanAttribute("deformable", piece.isDeformable(), true);
        writer.writeBooleanAttribute("texturable", piece.isTexturable(), true);
      }
      if (piece instanceof HomeFurnitureGroup) {
        BigDecimal price = piece.getPrice();
        // Ignore price of group if one of its children has a price
        for (HomePieceOfFurniture groupPiece : ((HomeFurnitureGroup)piece).getFurniture()) {
          if (groupPiece.getPrice() != null) {
            price = null;
            break;
          }
        }
        writer.writeBigDecimalAttribute("price", price);
      } else {
        writer.writeBigDecimalAttribute("price", piece.getPrice());
        writer.writeBigDecimalAttribute("valueAddedTaxPercentage", piece.getValueAddedTaxPercentage());
        writer.writeAttribute("currency", piece.getCurrency(), null);
      }
      writer.writeAttribute("staircaseCutOutShape", piece.getStaircaseCutOutShape(), null);
      if (piece instanceof HomeDoorOrWindow) {
        HomeDoorOrWindow doorOrWindow = (HomeDoorOrWindow)piece;
        writer.writeAttribute("cutOutShape", doorOrWindow.getCutOutShape(), null);
        writer.writeBooleanAttribute("wallCutOutOnBothSides", doorOrWindow.isWallCutOutOnBothSides(), false);
      }
      if ((getFlags() & HomeXMLFileRecorder.INCLUDE_VIEWER_DATA) == 0) {
        writer.writeFloatAttribute("dropOnTopElevation", piece.getDropOnTopElevation(), 1f);
        writer.writeBooleanAttribute("nameVisible", piece.isNameVisible(), false);
        writer.writeFloatAttribute("nameAngle", piece.getNameAngle(), 0f);
        writer.writeFloatAttribute("nameXOffset", piece.getNameXOffset(), 0f);
        writer.writeFloatAttribute("nameYOffset", piece.getNameYOffset(), 0f);
        if (piece instanceof HomeDoorOrWindow) {
          HomeDoorOrWindow doorOrWindow = (HomeDoorOrWindow)piece;
          writer.writeFloatAttribute("wallThickness", doorOrWindow.getWallThickness(), 1f);
          writer.writeFloatAttribute("wallDistance", doorOrWindow.getWallDistance(), 0f);
          writer.writeBooleanAttribute("widthDepthDeformable", doorOrWindow.isWidthDepthDeformable(), true);
          writer.writeBooleanAttribute("boundToWall", doorOrWindow.isBoundToWall(), true);
        } else if (piece instanceof HomeLight) {
          writer.writeFloatAttribute("power", ((HomeLight)piece).getPower());
        }
      }
    }

    @Override
    protected void writeChildren(XMLWriter writer, HomePieceOfFurniture piece) throws IOException {
      if ((getFlags() & HomeXMLFileRecorder.INCLUDE_VIEWER_DATA) != 0) {
        // Write subclass child elements
        if (piece instanceof HomeFurnitureGroup) {
          for (HomePieceOfFurniture groupPiece : ((HomeFurnitureGroup)piece).getFurniture()) {
            writePieceOfFurniture(writer, groupPiece);
          }
        }
        writeTexture(writer, piece.getTexture(), null);
        if (piece.getModelMaterials() != null) {
          for (HomeMaterial material : piece.getModelMaterials()) {
            writeMaterial(writer, material, piece.getModel());
          }
        }
      } else {
        super.writeChildren(writer, piece);
      }
    }
  }

  /**
   * Returns the string value of the given float, except for -1.0, 1.0 or 0.0
   * where -1, 1 and 0 is returned.
   */
  private static String floatToString(float f) {
    if (Math.abs(f) < 1E-6) {
      return "0";
    } else if (Math.abs(f - 1f) < 1E-6) {
      return "1";
    } else if (Math.abs(f + 1f) < 1E-6) {
      return "-1";
    } else {
      return String.valueOf(f);
    }
  }

  @Override
  protected void writeMaterial(XMLWriter writer, HomeMaterial material, final Content model) throws IOException {
    if (material != null) {
      new ObjectXMLExporter<HomeMaterial>() {
          @Override
          protected void writeAttributes(XMLWriter writer, HomeMaterial material) throws IOException {
            String name = material.getName();
            writer.writeAttribute("name", name);
            String key = getMaterialKey(model, name);
            if (!key.equals(name)) {
              writer.writeAttribute("key", key);
            }
            writer.writeColorAttribute("color", material.getColor());
            if (material.getShininess() != null) {
              writer.writeFloatAttribute("shininess", material.getShininess());
            }
          }

          @Override
          protected void writeChildren(XMLWriter writer, HomeMaterial material) throws IOException {
            writeTexture(writer, material.getTexture(), null);
          }
        }.writeElement(writer, material);
    }
  }

  protected String getMaterialKey(Content content, String materialName) {
    Map<String, String> materialUpdatedKeys = this.contentMaterialUpdatedKeys.get(content);
    if (materialUpdatedKeys != null) {
      String updatedKey = materialUpdatedKeys.get(materialName);
      if (updatedKey != null) {
        return updatedKey;
      }
    }
    return materialName;
  }

  @Override
  protected String getExportedContentName(Object owner, Content content) {
    if (content == null) {
      return null;
    } else if (content instanceof ResourceURLContent
               || content instanceof TemporaryURLContent) {
      throw new IllegalArgumentException("Invalid content of class " + content.getClass().getName()
          + " (home should reference only its own entries or external URLs)");
    } else if (content.getClass().getName().equals("com.eteks.sweethome3d.io.HomeURLContent")) {
      // Keep track of saved content
      this.referencedContents.add(content);
      return content instanceof URLContent && ((URLContent)content).isJAREntry()
          ? ((URLContent)content).getJAREntryName()
          : content.toString();
    } else {
      return ((URLContent)content).getURL().toString();
    }
  }

  protected String getModelExportedContentName(Object owner, Content content) throws IOException {
    if ((getFlags() & HomeXMLFileRecorder.CONVERT_MODELS_TO_OBJ_FORMAT) != 0
        && content != null
        && (content instanceof URLContent)
        && !((URLContent)content).getURL().toString().endsWith(".obj")) {
      String modelFileName = "model.obj";
      String entryContent = ((URLContent)content).getJAREntryName();
      int slashIndex = entryContent.indexOf('/');
      String exportedEntry;
      if (slashIndex == -1) {
        exportedEntry = entryContent + "/" + modelFileName;
      } else {
        exportedEntry = entryContent.substring(0, slashIndex + 1) + modelFileName;
      }

      if (!this.referencedContents.contains(new URLContent(new URL(((URLContent)content).getURL(), "/" + exportedEntry)))) {
        // Convert model to OBJ format
        BranchGroup model = ModelManager.getInstance().loadModel(content);
        File tempModelFile = OperatingSystem.createTemporaryFile("model", ".zip");
        Map<String, Appearance> materialAppearances = new LinkedHashMap<String, Appearance>();
        OBJWriter.writeNodeInZIPFile(model, materialAppearances, tempModelFile, 0, modelFileName, "Export for compatibility");
        // Check material names which were updated in the updated OBJ file
        HashMap<String, String> materialUpdatedKeys = null;
        for (Map.Entry<String, Appearance> appearanceEntry : materialAppearances.entrySet()) {
          String materialName = appearanceEntry.getKey();
          String appearanceName = appearanceEntry.getValue().getName();
          if (!materialName.equals(appearanceName)) {
            if (materialUpdatedKeys == null) {
              materialUpdatedKeys = new HashMap<String, String>();
              this.contentMaterialUpdatedKeys.put(content, materialUpdatedKeys);
            }
            materialUpdatedKeys.put(appearanceName, materialName);
          }
        }
        URLContent exportedContent = new RedirectedURLContent(new URL(((URLContent)content).getURL(), "/" + exportedEntry),
            new URLContent(new URL("jar:" + tempModelFile.toURI().toURL() + "!/" + modelFileName)));
        // Keep track of saved content
        this.referencedContents.add(exportedContent);
      }

      return exportedEntry;
    } else {
      return getExportedContentName(owner, content);
    }
  }
}
