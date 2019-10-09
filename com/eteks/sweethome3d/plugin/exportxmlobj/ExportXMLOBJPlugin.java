/*
 * ExportXMLOBJPlugin.java 
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
package com.eteks.sweethome3d.plugin.exportxmlobj;

import java.io.File;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;

import javax.jnlp.BasicService;
import javax.jnlp.ServiceManager;
import javax.jnlp.UnavailableServiceException;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.filechooser.FileFilter;

import com.eteks.sweethome3d.model.HomeRecorder;
import com.eteks.sweethome3d.model.InterruptedRecorderException;
import com.eteks.sweethome3d.model.RecorderException;
import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;
import com.eteks.sweethome3d.plugin.exportxml.HomeXMLFileRecorder;
import com.eteks.sweethome3d.swing.FileContentManager;
import com.eteks.sweethome3d.swing.SwingViewFactory;
import com.eteks.sweethome3d.viewcontroller.ContentManager;
import com.eteks.sweethome3d.viewcontroller.HomeView;
import com.eteks.sweethome3d.viewcontroller.ThreadedTaskController;

/**
 * A plug-in that generates a zip file containing a XML entry and 3D models at OBJ format (compatible with Sweet Home 3D JS).
 * @author Emmanuel Puybaret
 */
public class ExportXMLOBJPlugin extends Plugin {
  @Override
  public PluginAction [] getActions() {
    return new PluginAction [] {new ExportXMLOBJPluginAction("com.eteks.sweethome3d.plugin.exportxmlobj.ApplicationPlugin", 
        "EXPORT_TO_XML_OBJ", getPluginClassLoader(), true)};
  }

  protected class ExportXMLOBJPluginAction extends PluginAction {
    private String resourceBaseName;

    public ExportXMLOBJPluginAction(String resourceBaseName,  String actionPrefix, ClassLoader pluginClassLoader, boolean enabled) {
      super(resourceBaseName, actionPrefix, pluginClassLoader, enabled);
      this.resourceBaseName = resourceBaseName;
    }
  
    /**
     * Exports edited home.
     */
    public void execute() {
      final ResourceBundle resource = ResourceBundle.getBundle(this.resourceBaseName, 
          Locale.getDefault(), getPluginClassLoader());
      final HomeView homeView = getHomeController().getView();
      
      try {
        // Ignore plug-in in protected Java Web Start environment 
        ServiceManager.lookup("javax.jnlp.FileSaveService");
        // Use an uneditable editor pane to let user select text in dialog
        JEditorPane messagePane = new JEditorPane("text/html", 
            resource.getString("exportXMLOBJJavaWebStartInfo.message"));
        messagePane.setOpaque(false);
        messagePane.setEditable(false);
        try { 
          // Lookup the javax.jnlp.BasicService object 
          final BasicService service = (BasicService)ServiceManager.lookup("javax.jnlp.BasicService");
          // If basic service supports  web browser
          if (service.isWebBrowserSupported()) {
            // Add a listener that displays hyperlinks content in browser
            messagePane.addHyperlinkListener(new HyperlinkListener() {
              public void hyperlinkUpdate(HyperlinkEvent ev) {
                if (ev.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                  service.showDocument(ev.getURL()); 
                }
              }
            });
          }
        } catch (UnavailableServiceException ex) {
          // Too bad : service is unavailable             
        } 
        
        String title = resource.getString("exportXMLOBJJavaWebStartInfo.title");
        JOptionPane.showMessageDialog((JComponent)homeView, messagePane, title, JOptionPane.WARNING_MESSAGE);
        return;
      } catch (UnavailableServiceException ex) {
      }
      
      ContentManager contentManagerWithZipExtension = new FileContentManager(getUserPreferences()) {
        private final String ZIP_EXTENSION = ".zip";
        private final FileFilter ZIP_FILE_FILTER = new FileFilter() {
          @Override
          public boolean accept(File file) {
            // Accept directories and ZIP files
            return file.isDirectory()
                || file.getName().toLowerCase().endsWith(ZIP_EXTENSION);
          }
          
          @Override
          public String getDescription() {
            return "ZIP";
          }
        };
        
        @Override
        public String getDefaultFileExtension(ContentType contentType) {
          if (contentType == ContentType.USER_DEFINED) {
            return ZIP_EXTENSION;
          } else {
            return super.getDefaultFileExtension(contentType);
          }
        }
        
        @Override
        protected String [] getFileExtensions(ContentType contentType) {
          if (contentType == ContentType.USER_DEFINED) {
            return new String [] {ZIP_EXTENSION};
          } else {
            return super.getFileExtensions(contentType);
          }
        }
        
        @Override
        protected FileFilter [] getFileFilter(ContentType contentType) {
          if (contentType == ContentType.USER_DEFINED) {
            return new FileFilter [] {ZIP_FILE_FILTER};
          } else {
            return super.getFileFilter(contentType);
          }
        }
      };
      
      // Request a file name 
      final String exportedFile = contentManagerWithZipExtension.showSaveDialog(homeView,
          resource.getString("exportXMLOBJDialog.title"), 
          ContentManager.ContentType.USER_DEFINED, getHome().getName());
      if (exportedFile != null) {
        // Export to XML / OBJ in a threaded task
        Callable<Void> exportToObjTask = new Callable<Void>() {
          public Void call() throws RecorderException {
            getHomeRecorder().writeHome(getHome().clone(), exportedFile);
            return null;
          }
        };
        ThreadedTaskController.ExceptionHandler exceptionHandler = 
            new ThreadedTaskController.ExceptionHandler() {
          public void handleException(Exception ex) {
            if (!(ex instanceof InterruptedRecorderException)) {
              ex.printStackTrace();
              getHomeController().getView().showError(
                  String.format(resource.getString("exportXMLOBJError.message"), ex.getMessage()));
            }
          }
        };
        new ThreadedTaskController(exportToObjTask, 
            resource.getString("exportXMLOBJMessage"), exceptionHandler, 
            getUserPreferences(), new SwingViewFactory()).executeTask(homeView);
      }
    }
    
    protected HomeRecorder getHomeRecorder() {
      return new HomeXMLFileRecorder(9, 
          HomeXMLFileRecorder.INCLUDE_HOME_STRUCTURE 
          | HomeXMLFileRecorder.INCLUDE_ICONS
          | HomeXMLFileRecorder.CONVERT_MODELS_TO_OBJ_FORMAT);
    }
  }
}
