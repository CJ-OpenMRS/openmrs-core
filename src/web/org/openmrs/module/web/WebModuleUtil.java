package org.openmrs.module.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;
import java.util.Map.Entry;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.ServiceContext;
import org.openmrs.module.Module;
import org.openmrs.module.ModuleException;
import org.openmrs.module.ModuleFactory;
import org.openmrs.util.OpenmrsClassLoader;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.web.DispatcherServlet;
import org.openmrs.web.dwr.OpenmrsDWRServlet;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.context.support.XmlWebApplicationContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class WebModuleUtil {
	
	private static Log log = LogFactory.getLog(WebModuleUtil.class);
	
	private static DispatcherServlet dispatcherServlet = null;
	private static OpenmrsDWRServlet dwrServlet = null;
	
	// caches all of the modules' mapped servlets
	private static Map<Module, Map<String, HttpServlet>> moduleServlets = Collections.synchronizedMap(new HashMap<Module, Map<String, HttpServlet>>());
	
	/**
	 * Performs the webapp specific startup needs for modules
	 * 
	 * Normal startup is done in ModuleFactory.startModule(org.openmrs.module.Module)
	 * 
	 * @param mod
	 * @param ServletContext 
	 */
	public static void startModule(Module mod, ServletContext servletContext) {
		
		// only try and start this module if the api started it without a problem.
		log.debug("trying to start " + mod);
		if (ModuleFactory.isModuleStarted(mod) && !mod.hasStartupError()) {
			
			String realPath = servletContext.getRealPath("");
			
			// copy the messages into the webapp
			String path = "/WEB-INF/module_messages@LANG@.properties";
			
			for (Entry<String, Properties> entry : mod.getMessages().entrySet()) {
				log.debug("Copying message property file: " + entry.getKey());
				
				String lang = "_" + entry.getKey();
				if (lang.equals("_en") || lang.equals("_"))
					lang = "";
				
				String currentPath = path.replace("@LANG@", lang);
				
				OutputStream outStream = null;
				try {
					String absolutePath = realPath + currentPath;
					File file = new File(absolutePath);
					
					if (!file.exists())
						file.createNewFile();
					
					outStream = new FileOutputStream(file, true);
					
					Properties props = entry.getValue();
					
					// set all properties to start with 'moduleName.' if not already
					List<Object> keys = new Vector<Object>();
					keys.addAll(props.keySet());
					for (Object obj : keys) {
						String key = (String)obj;
						if (!key.startsWith(mod.getModuleId())) {
							props.put(mod.getModuleId() + "." + key, props.get(key));
							props.remove(key);
						}
					}
					
					// append the properties to the appropriate messages file
					props.store(outStream, "Module: " + mod.getName() + " v" + mod.getVersion());
				}
				catch (IOException e) {
					log.error("Unable to load in lang: '" + entry.getKey() + "' properties for mod: " + mod.getName(), e);
				}
				finally {
					if (outStream != null) {
						try {
							outStream.close();
						}
						catch (IOException e) {
							log.warn("Couldn't close outStream", e);
						}
					}
				}
				
			}
			log.debug("Done copying messages");
			
			// flag to tell whether we added any xml/dwr/etc changes that necessitate a refresh
			// of the web application context
			boolean refreshContext = false;
			
			// copy xml and html files into the webapp (from /web/module/ in the module)
			JarFile jarFile = null;
			try {
				File modFile = mod.getFile();
				jarFile = new JarFile(modFile);
				Enumeration<JarEntry> entries = jarFile.entries();
				
				while (entries.hasMoreElements()) {
					JarEntry entry = entries.nextElement();
					String name = entry.getName();
					log.debug("Entry name: " + name);
					if (name.startsWith("web/module/")) {
						// trim out the starting path of "web/module/"
						String filepath = name.substring(11);
						String absPath = realPath + "/WEB-INF/view/module/" + mod.getModuleId() + "/" + filepath;
						// get the output file
						File outFile = new File(absPath.replace("/", File.separator));
						if (!outFile.exists() && entry.isDirectory())
							outFile.mkdirs();
						else {
							//if (outFile.getName().endsWith(".jsp") == false)
							//	outFile = new File(absPath.replace("/", File.separator) + MODULE_NON_JSP_EXTENSION);
							
							// copy the contents over to the webpp for non directories
							OutputStream outStream = new FileOutputStream(outFile, false);
							InputStream inStream = jarFile.getInputStream(entry);
							OpenmrsUtil.copyFile(inStream, outStream);
							inStream.close();
							outStream.close();
						}
					}
					else if (name.endsWith(".xml") && 
								name.contains("/") == false && // eliminate xml files that aren't in the root
								!name.equals("config.xml")) {
						// write the .xml files to WEB-INF
						String absPath = realPath + "/WEB-INF/" + name;
						if (name.equals(mod.getModuleId() + "Context.xml")) {
							// set the name of the context file 
							absPath = realPath + "/WEB-INF/module-" + mod.getModuleId() + "-context.xml";
						}
						File outFile = new File(absPath.replace("/", File.separator));
						refreshContext = true;
						OutputStream outStream = new FileOutputStream(outFile, false);
						InputStream inStream = jarFile.getInputStream(entry);
						OpenmrsUtil.copyFile(inStream, outStream);
						inStream.close();
						outStream.close();
						
						// this will usually only be used when an improper shutdown has occurred.
						outFile.deleteOnExit(); // delete the xml file on JVM exit 
					}
				}
			}
			catch (IOException io) {
				log.warn("Unable to copy files from module " + mod.getModuleId() + " to the web layer", io);
			}
			finally {
				if (jarFile != null) {
					try {
						jarFile.close();
					}
					catch (IOException io) {
						log.warn("Couldn't close jar file: " + jarFile.getName(), io);
					}
				}
			}
				
			// find and add the dwr code to the dwr-modules.xml file (if defined)
			InputStream inputStream = null;
			try {
				Document config = mod.getConfig();
				Element root = config.getDocumentElement();
				if (root.getElementsByTagName("dwr").getLength() > 0) {
					
					// get the dwr-module.xml file that we're appending our code to
					File f = new File(realPath + "/WEB-INF/dwr-modules.xml".replace("/", File.separator));
					inputStream = new FileInputStream(f);
					Document dwrmodulexml = getDWRModuleXML(inputStream, realPath);
					Element outputRoot = dwrmodulexml.getDocumentElement();
					
					// loop over all of the children of the "dwr" tag
					Node node = root.getElementsByTagName("dwr").item(0);
					Node current = node.getFirstChild();
					while (current != null) {
						if ("allow".equals(current.getNodeName()) ||
							"signatures".equals(current.getNodeName())) {
								((Element)current).setAttribute("moduleId", mod.getModuleId());
								outputRoot.appendChild(dwrmodulexml.importNode(current, true));
						}
						
						current = current.getNextSibling();
					}
					
					refreshContext = true;
					
					// save the dwr-modules.xml file.
					OpenmrsUtil.saveDocument(dwrmodulexml, f);
				}
			}
			catch (FileNotFoundException e) {
				throw new ModuleException("/WEB-INF/dwr-modules.xml file doesn't exist.", e);
			}
			finally  {
				if (inputStream != null) {
					try {
						inputStream.close();					
					}
					catch (IOException io) {
						log.error("Error while closing input stream", io);
					}
				}
			}
			
			// refresh the spring web context to get the just-created xml 
			// files into it (if we copied an xml file)
			if (refreshContext) {
				try {
					
					// must "refresh" the spring dispatcherservlet as well to add in 
					//the new handlerMappings
					if (dispatcherServlet != null)
						dispatcherServlet.reInitFrameworkServlet();
						
					refreshWAC(servletContext);
					log.debug("Done Refreshing WAC");
					
					if (dwrServlet != null)
						dwrServlet.reInitServlet();
					
				}
				catch (Exception e) {
					String msg = "Unable to refresh the WebApplicationContext"; 
					log.warn(msg + " for module: " + mod.getModuleId(), e);
					mod.setStartupErrorMessage(msg + " : " + e.getMessage());
					
					try {
						stopModule(mod, servletContext, true);
					}
					catch (Exception e2) {
						// exception expected with most modules here
						log.warn("Error while stopping a module that had an error on refreshWAC", e);
					}
					System.gc();
					
					// try starting the application context again
					refreshWAC(servletContext);
				}
				
				// reload the advice points that were lost when refreshing Spring
				log.debug("Reloading advice for all started modules: " + ModuleFactory.getStartedModules().size());
				for (Module module : ModuleFactory.getStartedModules()) {
					ModuleFactory.loadAdvice(module);
				}
			}
			
			// mark to delete the entire module web directory on exit 
			// this will usually only be used when an improper shutdown has occurred.
			String folderPath = realPath + "/WEB-INF/view/module/" + mod.getModuleId();
			File outFile = new File(folderPath.replace("/", File.separator));
			outFile.deleteOnExit();
			
			
			// find and cache the module's servlets
			Element rootNode = mod.getConfig().getDocumentElement();
			NodeList servletTags = rootNode.getElementsByTagName("servlet");
			Map<String, HttpServlet> servletMap = new HashMap<String, HttpServlet>();
			
			for (int i=0; i< servletTags.getLength(); i++) {
				Node node = servletTags.item(i);
				NodeList childNodes = node.getChildNodes();
				String name = "", className = "";
				for (int j=0; j < childNodes.getLength(); j++) {
					Node childNode = childNodes.item(j);
					if ("servlet-name".equals(childNode.getNodeName()))
						name = childNode.getTextContent();
					else if("servlet-class".equals(childNode.getNodeName()))
						className = childNode.getTextContent();
				}
				if (name.length() == 0 || className.length() == 0) {
					log.warn("both 'servlet-name' and 'servlet-class' are required for the 'servlet' tag. Given '" + name + "' and '" + className + "' for module " + mod.getName());
					continue;
				}
				
				
				HttpServlet httpServlet = null;
				try {
					httpServlet = (HttpServlet)ModuleFactory.getModuleClassLoader(mod).loadClass(className).newInstance();
				}
				catch (ClassNotFoundException e) {
					log.warn("Class not found for servlet " + name + " for module " + mod.getName(), e);
					continue;
				}
				catch (IllegalAccessException e) {
					log.warn("Class cannot be accessed for servlet " + name + " for module " + mod.getName(), e);
					continue;
				}
				catch (InstantiationException e) {
					log.warn("Class cannot be instantiated for servlet " + name + " for module " + mod.getName(), e);
					continue;
				}
				
				servletMap.put(name, httpServlet);
			}
			moduleServlets.put(mod, servletMap);
		}
		
	}

	/**
	 * 
	 * @param inputStream
	 * @param realPath
	 * @return
	 */
	private static Document getDWRModuleXML(InputStream inputStream, String realPath) {
		Document dwrmodulexml = null;
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			
			dwrmodulexml = db.parse(inputStream);
		}
		catch (Exception e) {
			throw new ModuleException("Error parsing dwr-modules.xml file", e);
		}
		
		return dwrmodulexml;
	}

	/**
	 * Reverses all activities done by startModule(org.openmrs.module.Module)
	 * 
	 * Normal stop/shutdown is done by ModuleFactory
	 *
	 */
	public static void shutdownModules(ServletContext servletContext) {
		
		String realPath = servletContext.getRealPath("");
		
		// clear the module messages
		String messagesPath = realPath + "/WEB-INF/";
		File folder = new File(messagesPath.replace("/", File.separator));
		
		if (folder.exists()) {
			Properties emptyProperties = new Properties();
			for (File f : folder.listFiles()) {
				if (f.getName().startsWith("module_messages")) {
					OutputStream outStream = null;
					try {
						outStream = new FileOutputStream(f, false);
						emptyProperties.store(outStream, "");
					}
					catch (IOException io) {
						log.warn("Unable to clear module messages: " + f.getAbsolutePath(), io);
					}
					finally {
						if (outStream != null) {
							try {
								outStream.close();
							}
							catch (IOException e) {
								log.warn("Couldn't close outStream for file: " + f.getName(), e);
							}
						}
					}
					
				}
			}
		}
		
		// call web shutdown for each module 
		for (Module mod : ModuleFactory.getLoadedModules()) {
			stopModule(mod, servletContext, true);
		}
		
	}
	
	/**
	 * Reverses all visible activities done by startModule(org.openmrs.module.Module)
	 * 
	 * @param mod
	 * @param servletContext
	 */
	public static void stopModule(Module mod, ServletContext servletContext) {
		stopModule(mod, servletContext, false);
	}
	
	/**
	 * 
	 * Reverses all visible activities done by startModule(org.openmrs.module.Module)
	 * 
	 * @param mod
	 * @param servletContext
	 * @param skipRefresh
	 */
	private static void stopModule(Module mod, ServletContext servletContext, boolean skipRefresh) {
		
		String realPath = servletContext.getRealPath("");
		
		// delete the web files from the webapp
		String absPath = realPath + "/WEB-INF/view/module/" + mod.getModuleId();
		File moduleWebFolder = new File(absPath.replace("/", File.separator));
		if (moduleWebFolder.exists()) {
			try {
				OpenmrsUtil.deleteDirectory(moduleWebFolder);
			}
			catch (IOException io) {
				log.warn("Couldn't delete: " + moduleWebFolder.getAbsolutePath(), io);
			}
		}
		
		// delete the context file for this module
		absPath = realPath + "/WEB-INF/module-" + mod.getModuleId() + "-context.xml";
		File moduleContextXml = new File(absPath.replace("/", File.separator));
		if (moduleContextXml.exists()) {
			System.gc();
			if (!moduleContextXml.delete()) {
				moduleContextXml.deleteOnExit();
				log.warn("Unable to delete moduleContext: " + moduleContextXml.getAbsolutePath());
			}
		}
		else
			log.warn("No module context xml file found for " + mod.getModuleId());
		
		// delete the xml files for this module
		JarFile jarFile = null;
		try {
			File modFile = mod.getFile();
			jarFile = new JarFile(modFile);
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				log.debug("Entry name: " + entry.getName());
				if (entry.getName().endsWith(".xml") && 
						!entry.getName().equals("config.xml") &&
						!entry.getName().endsWith(mod.getModuleId() + "context.xml")) {
					absPath = realPath + "/WEB-INF/" + entry.getName();
					File moduleXmlFile = new File(absPath.replace("/", File.separator));
					if (moduleXmlFile.exists()) {
						System.gc();
						if (!moduleXmlFile.delete()) {
							moduleXmlFile.deleteOnExit();
							log.warn("Unable to delete xml file: " + moduleXmlFile.getAbsolutePath());
						}
					}
				}
			}
		}
		catch (IOException io) {
			log.warn("Unable to delete files from module " + mod.getModuleId() + " in the web layer", io);
		}
		finally {
			if (jarFile != null) {
				try {
					jarFile.close();
				}
				catch (IOException io) {
					log.warn("Couldn't close jar file: " + jarFile.getName(), io);
				}
			}
		}
			
		// (not) deleting module message properties
		
		// remove the module's servlets
		moduleServlets.remove(mod);

		// remove this module's entries in the dwr xml file
		InputStream inputStream = null;
		try {
			Document config = mod.getConfig();
			Element root = config.getDocumentElement();
			// if they defined any xml element
			if (root.getElementsByTagName("dwr").getLength() > 0) {
				
				// get the dwr-module.xml file that we're appending our code to
				File f = new File(realPath + "/WEB-INF/dwr-modules.xml".replace("/", File.separator));
				inputStream = new FileInputStream(f);
				Document dwrmodulexml = getDWRModuleXML(inputStream, realPath);
				Element outputRoot = dwrmodulexml.getDocumentElement();
				
				// loop over all of the children of the "dwr" tag
				// and remove all "allow" and "signature" tags that have the
				// same moduleId attr as the module being stopped
				NodeList nodeList = outputRoot.getChildNodes();
				int i = 0;
				while (i < nodeList.getLength()) {
					Node current = nodeList.item(i);
					if ("allow".equals(current.getNodeName()) || 
						"signatures".equals(current.getNodeName())) {
							NamedNodeMap attrs = current.getAttributes();
							Node attr = attrs.getNamedItem("moduleId");
							if (attr != null && mod.getModuleId().equals(attr.getNodeValue())) {
								outputRoot.removeChild(current);
							}
					}
					else
						i++;
				}
				
				// save the dwr-modules.xml file.
				OpenmrsUtil.saveDocument(dwrmodulexml, f);
			}
		}
		catch (FileNotFoundException e) {
			throw new ModuleException("/WEB-INF/dwr-modules.xml file doesn't exist.", e);
		}
		finally  {
			if (inputStream != null) {
				try {
					inputStream.close();					
				}
				catch (IOException io) {
					log.error("Error while closing input stream", io);
				}
			}
		}
		
		try {
			if (dispatcherServlet != null)
				dispatcherServlet.reInitFrameworkServlet();
			if (dwrServlet != null)
				dwrServlet.reInitServlet();
		}
		catch (ServletException se) {
			log.warn("Unable to reinitialize webapplicationcontext for dispatcherservlet for module: " + mod.getName(), se);
		}
		
		if (!skipRefresh)
			refreshWAC(servletContext);
		
	}
	
	/**
	 * Stops, closes, and refreshes the Spring context for the given <code>servletContext</code>
	 * 
	 * @param servletContext
	 * @return
	 */
	public static XmlWebApplicationContext refreshWAC(ServletContext servletContext) {
		XmlWebApplicationContext wac = (XmlWebApplicationContext)WebApplicationContextUtils.getWebApplicationContext(servletContext);
		log.debug("WAC class: " + wac.getClass().getName());
		ServiceContext.destroyInstance();
		try {
			wac.stop();
			wac.close();
		}
		catch (Exception e) {
			// Spring seems to be trying to refresh the context instead of /just/ stopping
			// pass
		}
		
		OpenmrsClassLoader.destroyInstance();
		wac.setClassLoader(OpenmrsClassLoader.getInstance());
		Thread.currentThread().setContextClassLoader(OpenmrsClassLoader.getInstance());

		ServiceContext.getInstance().startRefreshingContext();
		wac.refresh();
		ServiceContext.getInstance().doneRefreshingContext();
		
		return wac;
	}
	
	/**
	 * Save the dispatcher servlet for use later (reinitializing things)
	 * @param ds
	 */
	public static void setDispatcherServlet(DispatcherServlet ds) {
		log.debug("Setting dispatcher servlet: " + ds);
		dispatcherServlet = ds;
	}
	
	/**
	 * Save the dwr servlet for use later (reinitializing things)
	 * @param ds
	 */
	public static void setDWRServlet(OpenmrsDWRServlet ds) {
		log.debug("Setting dwr servlet: " + ds);
		dwrServlet = ds;
		//new NewCreator();
		//SessionFactoryUtils.processDeferredClose(null);
	}
	
	/**
	 * Finds the servlet defined by the servlet name
	 * 
	 */
	public static HttpServlet getServlet(Module mod, String servletName) {
		Map<String, HttpServlet> servlets = moduleServlets.get(mod);
		
		if (servlets != null && servlets.containsKey(servletName))
			return servlets.get(servletName);
		
		return null;
	}
}
