//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.optional.webinterface;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.logging.Logger;

import jd.OptionalPluginWrapper;
import jd.config.Configuration;
import jd.config.SubConfiguration;
import jd.controlling.DistributeData;
import jd.controlling.DownloadController;
import jd.controlling.LinkGrabberController;
import jd.controlling.PasswordListController;
import jd.controlling.reconnect.Reconnecter;
import jd.gui.skins.simple.components.Linkgrabber.LinkGrabberFilePackage;
import jd.gui.skins.simple.components.Linkgrabber.LinkGrabberPanel;
import jd.http.Encoding;
import jd.nutils.Formatter;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.LinkStatus;
import jd.utils.JDUtilities;

public class JDSimpleWebserverRequestHandler {

    // private SubConfiguration guiConfig = null;
    private HashMap<String, String> headers;

    private Logger logger = jd.controlling.JDLogger.getLogger();
    private JDSimpleWebserverResponseCreator response;

    private LinkGrabberController lgi;

    public JDSimpleWebserverRequestHandler(HashMap<String, String> headers, JDSimpleWebserverResponseCreator response) {
        lgi = LinkGrabberController.getInstance();
        this.response = response;
        this.headers = headers;
    }

    public void handle() {

        String request = headers.get(null);

        String[] requ = request.split(" ");

        String cPath = requ[1];
        String path, querry;
        path = cPath.substring(1);
        String[] params;
        HashMap<String, String> requestParameter = new HashMap<String, String>();

        /* bekanntgebung der mehrfach belegbaren parameter */
        requestParameter.put("package_all_downloads_counter", "0");
        requestParameter.put("package_single_download_counter", "0");
        requestParameter.put("package_all_add_counter", "0");
        requestParameter.put("package_single_add_counter", "0");

        if (cPath.indexOf("?") >= 0) {
            querry = cPath.substring(cPath.indexOf("?") + 1);
            path = cPath.substring(1, cPath.indexOf("?"));
            params = querry.split("\\&");

            for (String entry : params) {
                entry = entry.trim();
                int index = entry.indexOf("=");
                String key = entry;

                String value = null;
                if (index >= 0) {
                    key = entry.substring(0, index);
                    value = entry.substring(index + 1);
                }

                if (requestParameter.containsKey(key) || requestParameter.containsKey(key + "_counter")) {
                    /*
                     * keys mit _counter können mehrfach belegt werden, müssen
                     * vorher aber bekannt gegeben werden
                     */
                    if (requestParameter.containsKey(key + "_counter")) {
                        Integer keycounter = 0;
                        keycounter = Formatter.filterInt(requestParameter.get(key + "_counter"));
                        keycounter++;
                        requestParameter.put(key + "_counter", keycounter.toString());
                        requestParameter.put(key + "_" + keycounter.toString(), value);
                    }
                } else {
                    requestParameter.put(key, value);
                }
            }
        }
        String url = path.replaceAll("\\.\\.", "");

        /* parsen der paramter */
        if (requestParameter.containsKey("do")) {
            if (requestParameter.get("do").compareToIgnoreCase("submit") == 0) {
                if (requestParameter.containsKey("speed")) {
                    int setspeed = Formatter.filterInt(requestParameter.get("speed"));
                    if (setspeed < 0) {
                        setspeed = 0;
                    }
                    SubConfiguration.getConfig("DOWNLOAD").setProperty(Configuration.PARAM_DOWNLOAD_MAX_SPEED, setspeed);
                }

                if (requestParameter.containsKey("maxdls")) {
                    int maxdls = Formatter.filterInt(requestParameter.get("maxdls"));
                    if (maxdls < 1) {
                        maxdls = 1;
                    }
                    SubConfiguration.getConfig("DOWNLOAD").setProperty(Configuration.PARAM_DOWNLOAD_MAX_SIMULTAN, maxdls);
                }

                if (!requestParameter.containsKey("selected_dowhat_link_adder")) {
                    if (requestParameter.containsKey("autoreconnect")) {
                        JDUtilities.getConfiguration().setProperty(Configuration.PARAM_DISABLE_RECONNECT, false);
                    } else {
                        JDUtilities.getConfiguration().setProperty(Configuration.PARAM_DISABLE_RECONNECT, true);
                    }
                }
                if (requestParameter.containsKey("package_single_add_counter")) {
                    synchronized (lgi) {
                        /* aktionen in der adder liste ausführen */
                        Integer download_id = 0;
                        Integer package_id = 0;
                        String[] ids;
                        int counter_max = Formatter.filterInt(requestParameter.get("package_single_add_counter"));
                        int counter_index = 0;
                        DownloadLink link;
                        ArrayList<DownloadLink> links = new ArrayList<DownloadLink>();
                        ArrayList<LinkGrabberFilePackage> packages = new ArrayList<LinkGrabberFilePackage>();
                        for (counter_index = 1; counter_index <= counter_max; counter_index++) {
                            if (requestParameter.containsKey("package_single_add_" + counter_index)) {
                                ids = requestParameter.get("package_single_add_" + counter_index).toString().split("[+]", 2);
                                package_id = Formatter.filterInt(ids[0].toString());
                                download_id = Formatter.filterInt(ids[1].toString());
                                links.add(lgi.getPackages().get(package_id).get(download_id));
                                if (!packages.contains(lgi.getPackages().get(package_id))) packages.add(lgi.getPackages().get(package_id));
                            }
                        }
                        if (requestParameter.containsKey("selected_dowhat_link_adder")) {
                            String dowhat = requestParameter.get("selected_dowhat_link_adder");
                            /* packages-namen des link-adders aktuell halten */

                            for (int i = 0; i < lgi.getPackages().size(); i++) {
                                if (requestParameter.containsKey("adder_package_name_" + i)) {
                                    lgi.getPackages().get(i).setName(Encoding.htmlDecode(requestParameter.get("adder_package_name_" + i).toString()));
                                }
                            }

                            if (dowhat.compareToIgnoreCase("remove") == 0) {
                                /* entfernen */
                                for (LinkGrabberFilePackage fp : packages) {
                                    fp.remove(links);
                                }
                            } else if (dowhat.compareToIgnoreCase("remove+offline") == 0) {
                                /* entfernen(offline) */
                                links = new ArrayList<DownloadLink>();
                                for (int i = 0; i < lgi.getPackages().size(); i++) {
                                    for (int ii = 0; ii < lgi.getPackages().get(i).size(); ii++) {
                                        links.add(lgi.getPackages().get(i).get(ii));
                                    }
                                }
                                for (Iterator<DownloadLink> it = links.iterator(); it.hasNext();) {
                                    link = it.next();
                                    if (link.isAvailabilityStatusChecked() == true && link.isAvailable() == false) {
                                        link.getFilePackage().remove(link);
                                    }
                                }
                            } else if (dowhat.compareToIgnoreCase("add") == 0) {
                                /* link adden */
                                for (LinkGrabberFilePackage fp : packages) {
                                    LinkGrabberPanel.getLinkGrabber().confirmPackage(fp, null);
                                }

                            }
                        }
                    }
                }

                if (requestParameter.containsKey("package_single_download_counter")) {

                    // Aktionen in der Download-liste ausführen
                    Integer download_id = 0;
                    Integer package_id = 0;
                    String[] ids;
                    int counter_max = Formatter.filterInt(requestParameter.get("package_single_download_counter"));
                    int counter_index = 0;
                    DownloadLink link;
                    ArrayList<DownloadLink> links = new ArrayList<DownloadLink>();
                    for (counter_index = 1; counter_index <= counter_max; counter_index++) {
                        if (requestParameter.containsKey("package_single_download_" + counter_index)) {
                            ids = requestParameter.get("package_single_download_" + counter_index).toString().split("[+]", 2);
                            package_id = Formatter.filterInt(ids[0].toString());
                            download_id = Formatter.filterInt(ids[1].toString());

                            links.add(JDUtilities.getController().getPackages().get(package_id).getDownloadLinkList().get(download_id));
                        }
                    }

                    if (requestParameter.containsKey("selected_dowhat_index")) {
                        String dowhat = requestParameter.get("selected_dowhat_index");
                        if (dowhat.compareToIgnoreCase("activate") == 0) {
                            /* aktivieren */
                            for (Iterator<DownloadLink> it = links.iterator(); it.hasNext();) {
                                link = it.next();
                                link.setEnabled(true);
                            }
                            DownloadController.getInstance().fireGlobalUpdate();
                        }
                        if (dowhat.compareToIgnoreCase("deactivate") == 0) {
                            /* deaktivieren */
                            for (Iterator<DownloadLink> it = links.iterator(); it.hasNext();) {
                                link = it.next();
                                link.setEnabled(false);
                            }
                            DownloadController.getInstance().fireGlobalUpdate();
                        }
                        if (dowhat.compareToIgnoreCase("reset") == 0) {
                            /*
                             * reset
                             */
                            for (Iterator<DownloadLink> it = links.iterator(); it.hasNext();) {
                                link = it.next();
                                link.getLinkStatus().setStatus(LinkStatus.TODO);
                                link.getLinkStatus().setStatusText("");
                                link.reset();
                            }
                            DownloadController.getInstance().fireGlobalUpdate();
                        }
                        if (dowhat.compareToIgnoreCase("remove") == 0) {

                            // entfernen
                            for (DownloadLink dl : links) {
                                dl.getFilePackage().remove(dl);
                            }
                        }
                        if (dowhat.compareToIgnoreCase("abort") == 0) {

                            // abbrechen
                            for (Iterator<DownloadLink> it = links.iterator(); it.hasNext();) {
                                link = it.next();
                                link.setAborted(true);
                            }
                            DownloadController.getInstance().fireGlobalUpdate();
                        }
                    }
                }

            } else if (requestParameter.get("do").compareToIgnoreCase("reconnect") == 0) {
                class JDReconnect implements Runnable {

                    // Zeitverzögertes neustarten
                    JDReconnect() {
                        new Thread(this).start();
                    }

                    public void run() {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {

                            jd.controlling.JDLogger.getLogger().log(java.util.logging.Level.SEVERE, "Exception occured", e);
                        }
                        boolean tmp = JDUtilities.getConfiguration().getBooleanProperty(Configuration.PARAM_DISABLE_RECONNECT, false);
                        JDUtilities.getConfiguration().setProperty(Configuration.PARAM_DISABLE_RECONNECT, false);
                        if (JDUtilities.getController().getRunningDownloadNum() > 0) {
                            JDUtilities.getController().stopDownloads();
                        }
                        if (Reconnecter.waitForNewIP(1)) {
                            logger.info("Reconnect erfolgreich");
                        } else {
                            logger.info("Reconnect fehlgeschlagen");
                        }
                        JDUtilities.getConfiguration().setProperty(Configuration.PARAM_DISABLE_RECONNECT, tmp);
                    }
                }
                @SuppressWarnings("unused")
                JDReconnect jdrc = new JDReconnect();

            } else if (requestParameter.get("do").compareToIgnoreCase("close") == 0) {
                class JDClose implements Runnable { /* zeitverzögertes beenden */
                    JDClose() {
                        new Thread(this).start();
                    }

                    public void run() {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            jd.controlling.JDLogger.getLogger().log(java.util.logging.Level.SEVERE, "Exception occured", e);
                        }
                        JDUtilities.getController().exit();
                    }
                }
                @SuppressWarnings("unused")
                JDClose jdc = new JDClose();

            } else if (requestParameter.get("do").compareToIgnoreCase("start") == 0) {
                JDUtilities.getController().startDownloads();
            } else if (requestParameter.get("do").compareToIgnoreCase("stop") == 0) {
                JDUtilities.getController().stopDownloads();
            } else if (requestParameter.get("do").compareToIgnoreCase("restart") == 0) {
                class JDRestart implements Runnable {

                    // Zeitverzögertes neustarten
                    JDRestart() {
                        new Thread(this).start();
                    }

                    public void run() {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {

                            jd.controlling.JDLogger.getLogger().log(java.util.logging.Level.SEVERE, "Exception occured", e);
                        }
                        JDUtilities.restartJD();
                    }
                }
                @SuppressWarnings("unused")
                JDRestart jdrs = new JDRestart();

            } else if (requestParameter.get("do").compareToIgnoreCase("add") == 0) {
                if (requestParameter.containsKey("addlinks")) {
                    String AddLinks = Encoding.htmlDecode(requestParameter.get("addlinks"));
                    ArrayList<DownloadLink> waitingLinkList = new DistributeData(AddLinks).findLinks();
                    DownloadLink[] linkList = waitingLinkList.toArray(new DownloadLink[] {});
                    LinkGrabberPanel.getLinkGrabber().addLinks(linkList);
                }
            } else if (requestParameter.get("do").compareToIgnoreCase("upload") == 0) {
                if (requestParameter.containsKey("file")) {
                    File container = JDUtilities.getResourceFile("container/" + requestParameter.get("file"));
                    ArrayList<DownloadLink> waitingLinkList = JDUtilities.getController().getContainerLinks(container);
                    DownloadLink[] linkList = waitingLinkList.toArray(new DownloadLink[] {});
                    LinkGrabberPanel.getLinkGrabber().addLinks(linkList);
                }
            }
        }
        /* passwortliste verändern */
        if (requestParameter.containsKey("passwd")) {
            if (requestParameter.get("passwd").compareToIgnoreCase("save") == 0) {
                if (requestParameter.containsKey("password_list")) {

                    String passwordList = Encoding.htmlDecode(requestParameter.get("password_list"));
                    for (OptionalPluginWrapper wrapper : OptionalPluginWrapper.getOptionalWrapper()) {
                        if (wrapper.isEnabled() && wrapper.getPlugin().getClass().getName().endsWith("JDUnrar")) {
                            ArrayList<String> pws = new ArrayList<String>();
                            for (String pw : Regex.getLines(passwordList)) {
                                pws.add(0, pw);
                            }
                            PasswordListController.getInstance().setPasswordList(pws);
                            break;
                        }
                    }

                }
            }
        }

        File fileToRead = JDUtilities.getResourceFile("plugins/webinterface/" + url);
        if (!fileToRead.isFile()) {
            /*
             * default soll zur index.tmpl gehen, fall keine angabe gemacht
             * wurde
             */
            String tempurl = url + "index.tmpl";
            File fileToRead2 = JDUtilities.getResourceFile("plugins/webinterface/" + tempurl);
            if (fileToRead2.isFile()) {
                url = tempurl;
                fileToRead = JDUtilities.getResourceFile("plugins/webinterface/" + url);
            }
        }

        if (!fileToRead.exists()) {
            response.setNotFound(url);
        } else {
            if (url.endsWith(".tmpl")) {
                JDSimpleWebserverTemplateFileRequestHandler filerequest;
                filerequest = new JDSimpleWebserverTemplateFileRequestHandler(response);
                filerequest.handleRequest(url, requestParameter);
            } else {
                JDSimpleWebserverStaticFileRequestHandler filerequest;
                filerequest = new JDSimpleWebserverStaticFileRequestHandler(response);
                filerequest.handleRequest(url, requestParameter);
            }
        }
    }
}