//jDownloader - Downloadmanager
//Copyright (C) 2011  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.plugins.hoster;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.Property;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.HTMLParser;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;
import jd.utils.locale.JDL;

import org.appwork.utils.formatter.SizeFormatter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "groovefile.com" }, urls = { "https?://(www\\.)?groovefile\\.com/[a-z0-9]{12}" }, flags = { 2 })
public class GrooveFileCom extends PluginForHost {

    private String              BRBEFORE            = "";
    private static final String PASSWORDTEXT        = "(<br><b>Password:</b> <input|<br><b>Passwort:</b> <input)";
    private static final String COOKIE_HOST         = "http://groovefile.com";
    private static final String MAINTENANCE         = ">This server is in maintenance mode";
    private static final String MAINTENANCEUSERTEXT = "This server is under Maintenance";
    private static final String ALLWAIT_SHORT       = "Waiting till new downloads can be started";

    public GrooveFileCom(final PluginWrapper wrapper) {
        super(wrapper);
        // this.enablePremium(COOKIE_HOST + "/premium.html");
        setConfigElements();
    }

    public void checkErrors(final DownloadLink theLink, final boolean checkAll, final String passCode) throws NumberFormatException, PluginException {
        if (checkAll) {
            if (new Regex(BRBEFORE, PASSWORDTEXT).matches() || BRBEFORE.contains("Wrong password")) {
                logger.warning("Wrong password, the entered password \"" + passCode + "\" is wrong, retrying...");
                throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
            }
            if (BRBEFORE.contains("Wrong captcha")) {
                logger.warning("Wrong captcha or wrong password!");
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            if (BRBEFORE.contains("\">Skipped countdown<")) { throw new PluginException(LinkStatus.ERROR_FATAL, "Fatal countdown error (countdown skipped)"); }
        }
        /** Waittime reconnect handling */
        if (new Regex(BRBEFORE, "(You have reached the download\\-limit|You have to wait)").matches()) {
            String tmphrs = new Regex(BRBEFORE, "\\s+(\\d+)\\s+hours?").getMatch(0);
            if (tmphrs == null) {
                tmphrs = new Regex(BRBEFORE, "You have to wait.*?\\s+(\\d+)\\s+hours?").getMatch(0);
            }
            String tmpmin = new Regex(BRBEFORE, "\\s+(\\d+)\\s+minutes?").getMatch(0);
            if (tmpmin == null) {
                tmpmin = new Regex(BRBEFORE, "You have to wait.*?\\s+(\\d+)\\s+minutes?").getMatch(0);
            }
            final String tmpsec = new Regex(BRBEFORE, "\\s+(\\d+)\\s+seconds?").getMatch(0);
            if (tmphrs == null && tmpmin == null && tmpsec == null) {
                logger.info("Waittime regexes seem to be broken");
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 60 * 60 * 1000l);
            } else {
                int minutes = 0, seconds = 0, hours = 0;
                if (tmphrs != null) {
                    hours = Integer.parseInt(tmphrs);
                }
                if (tmpmin != null) {
                    minutes = Integer.parseInt(tmpmin);
                }
                if (tmpsec != null) {
                    seconds = Integer.parseInt(tmpsec);
                }
                final int waittime = (3600 * hours + 60 * minutes + seconds + 1) * 1000;
                logger.info("Detected waittime #2, waiting " + waittime + "milliseconds");
                /** Not enough waittime to reconnect->Wait and try again */
                if (waittime < 180000) { throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.xfilesharingprobasic.allwait", ALLWAIT_SHORT), waittime); }
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, waittime);
            }
        }
        if (BRBEFORE.contains("You're using all download slots for IP")) { throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 10 * 60 * 1001l); }
        if (BRBEFORE.contains("Error happened when generating Download Link")) { throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error!", 10 * 60 * 1000l); }
        /** Errorhandling for only-premium links */
        if (new Regex(BRBEFORE, "( can download files up to |Upgrade your account to download bigger files|>Upgrade your account to download larger files|>The file You requested  reached max downloads limit for Free Users|Please Buy Premium To download this file<|This file reached max downloads limit)").matches()) {
            String filesizelimit = new Regex(BRBEFORE, "You can download files up to(.*?)only").getMatch(0);
            if (filesizelimit != null) {
                filesizelimit = filesizelimit.trim();
                logger.warning("As free user you can download files up to " + filesizelimit + " only");
                throw new PluginException(LinkStatus.ERROR_FATAL, "Free users can only download files up to " + filesizelimit);
            } else {
                logger.warning("Only downloadable via premium");
                throw new PluginException(LinkStatus.ERROR_FATAL, "Only downloadable via premium or registered");
            }
        }
        if (BRBEFORE.contains(MAINTENANCE)) { throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.xfilesharingprobasic.undermaintenance", MAINTENANCEUSERTEXT), 2 * 60 * 60 * 1000l); }
    }

    public void checkServerErrors() throws NumberFormatException, PluginException {
        if (new Regex(BRBEFORE, Pattern.compile("No file", Pattern.CASE_INSENSITIVE)).matches()) { throw new PluginException(LinkStatus.ERROR_FATAL, "Server error"); }
        if (new Regex(BRBEFORE, "(File Not Found|<h1>404 Not Found</h1>)").matches()) {
            logger.warning("Server says link offline, please recheck that!");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
    }

    private String decodeDownloadLink(final String s) {
        String decoded = null;

        try {
            final Regex params = new Regex(s, "\\'(.*?[^\\\\])\\',(\\d+),(\\d+),\\'(.*?)\\'");

            String p = params.getMatch(0).replaceAll("\\\\", "");
            final int a = Integer.parseInt(params.getMatch(1));
            int c = Integer.parseInt(params.getMatch(2));
            final String[] k = params.getMatch(3).split("\\|");

            while (c != 0) {
                c--;
                if (k[c].length() != 0) {
                    p = p.replaceAll("\\b" + Integer.toString(c, a) + "\\b", k[c]);
                }
            }

            decoded = p;
        } catch (final Exception e) {
        }

        String finallink = null;
        if (decoded != null) {
            finallink = new Regex(decoded, "name=\"src\"value=\"(.*?)\"").getMatch(0);
            if (finallink == null) {
                finallink = new Regex(decoded, "type=\"video/divx\"src=\"(.*?)\"").getMatch(0);
                if (finallink == null) {
                    finallink = new Regex(decoded, "\\.addVariable\\(\\'file\\',\\'(http://.*?)\\'\\)").getMatch(0);
                }
            }
        }
        return finallink;
    }

    public void doFree(final DownloadLink downloadLink, final boolean resumable, final int maxchunks, final boolean getLinkWithoutLogin) throws Exception, PluginException {
        String passCode = null;
        String md5hash = new Regex(BRBEFORE, "<b>MD5.*?</b>.*?nowrap>(.*?)<").getMatch(0);
        if (md5hash != null) {
            md5hash = md5hash.trim();
            logger.info("Found md5hash: " + md5hash);
            downloadLink.setMD5Hash(md5hash);
        }

        String dllink = null;
        if (getLinkWithoutLogin) {
            dllink = downloadLink.getStringProperty("freelink");
        } else {
            dllink = downloadLink.getStringProperty("freelink2");
        }
        dllink = downloadLink.getStringProperty("freelink");
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                con = br2.openGetConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    if (getLinkWithoutLogin) {
                        downloadLink.setProperty("freelink", Property.NULL);
                    } else {
                        downloadLink.setProperty("freelink2", Property.NULL);
                    }
                    dllink = null;
                }
            } catch (final Throwable e) {
                dllink = null;
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }

        /**
         * Videolinks can already be found here, if a link is found here we can
         * skip waittimes and captchas
         */
        if (dllink == null) {
            checkErrors(downloadLink, false, passCode);
            if (BRBEFORE.contains("\"download1\"")) {
                br.postPage(downloadLink.getDownloadURL(), "op=download1&usr_login=&id=" + new Regex(downloadLink.getDownloadURL(), COOKIE_HOST.replaceAll("https?://", "") + "/" + "([a-z0-9]{12})").getMatch(0) + "&fname=" + downloadLink.getName() + "&referer=&method_free=Free+Download");
                doSomething();
                checkErrors(downloadLink, false, passCode);
            }
            dllink = getDllink();
        }
        if (dllink == null) {
            Form dlForm = br.getFormbyProperty("name", "F1");
            if (dlForm == null) { throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT); }
            final long timeBefore = System.currentTimeMillis();
            boolean password = false;
            boolean skipWaittime = false;
            if (new Regex(BRBEFORE, PASSWORDTEXT).matches()) {
                password = true;
                logger.info("The downloadlink seems to be password protected.");
            }

            /* Captcha START */
            if (BRBEFORE.contains(";background:#ccc;text-align")) {
                logger.info("Detected captcha method \"plaintext captchas\" for this host");
                /** Captcha method by ManiacMansion */
                final String[][] letters = new Regex(Encoding.htmlDecode(br.toString()), "<span style=\\'position:absolute;padding\\-left:(\\d+)px;padding\\-top:\\d+px;\\'>(\\d)</span>").getMatches();
                if (letters == null || letters.length == 0) {
                    logger.warning("plaintext captchahandling broken!");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final SortedMap<Integer, String> capMap = new TreeMap<Integer, String>();
                for (final String[] letter : letters) {
                    capMap.put(Integer.parseInt(letter[0]), letter[1]);
                }
                final StringBuilder code = new StringBuilder();
                for (final String value : capMap.values()) {
                    code.append(value);
                }
                dlForm.put("code", code.toString());
                logger.info("Put captchacode " + code.toString() + " obtained by captcha metod \"plaintext captchas\" in the form.");
            } else if (BRBEFORE.contains("/captchas/")) {
                logger.info("Detected captcha method \"Standard captcha\" for this host");
                final String[] sitelinks = HTMLParser.getHttpLinks(br.toString(), null);
                String captchaurl = null;
                if (sitelinks == null || sitelinks.length == 0) {
                    logger.warning("Standard captcha captchahandling broken!");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                for (final String link : sitelinks) {
                    if (link.contains("/captchas/")) {
                        captchaurl = link;
                        break;
                    }
                }
                if (captchaurl == null) {
                    logger.warning("Standard captcha captchahandling broken!");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final String code = getCaptchaCode("xfilesharingprobasic", captchaurl, downloadLink);
                dlForm.put("code", code);
                logger.info("Put captchacode " + code + " obtained by captcha metod \"Standard captcha\" in the form.");
            } else if (new Regex(BRBEFORE, "(api\\.recaptcha\\.net|google\\.com/recaptcha/api/)").matches()) {
                logger.info("Detected captcha method \"Re Captcha\" for this host");
                final PluginForHost recplug = JDUtilities.getPluginForHost("DirectHTTP");
                final jd.plugins.hoster.DirectHTTP.Recaptcha rc = ((DirectHTTP) recplug).getReCaptcha(br);
                rc.setForm(dlForm);
                final String id = br.getRegex("\\?k=([A-Za-z0-9%_\\+\\- ]+)\"").getMatch(0);
                rc.setId(id);
                rc.load();
                final File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                final String c = getCaptchaCode(cf, downloadLink);
                rc.prepareForm(c);
                logger.info("Put captchacode " + c + " obtained by captcha metod \"Re Captcha\" in the form and submitted it.");
                dlForm = rc.getForm();
                /** waittime is often skippable for reCaptcha handling */
                skipWaittime = getPluginConfig().getBooleanProperty("SKIP_RECAPTCHA_WAITTIME", false);
            } else if (new Regex(BRBEFORE, "api\\.solvemedia\\.com/papi").matches()) {
                skipWaittime = true;
                logger.info("Detected captcha method \"solvemedia\" for this host");
                String captchaurl = br.getRegex("<iframe src=\"(http://api\\.solvemedia\\.com/papi/challenge\\.noscript\\?k=[\\w-]+)\"").getMatch(0);
                if (captchaurl == null) { throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT); }

                final boolean skipcaptcha = getPluginConfig().getBooleanProperty("SKIP_CAPTCHA", true);
                final Browser br2 = br.cloneBrowser();
                br2.getPage(captchaurl);
                captchaurl = br2.getRegex("<img src=\"(.*?)\"").getMatch(0);
                String adcopyChallenge = br2.getRegex("id=\"adcopy_challenge\" value=\"(.*?)\"").getMatch(0);
                adcopyChallenge = adcopyChallenge == null ? new Regex(captchaurl, "c=(.*?)").getMatch(0) : adcopyChallenge;
                if (adcopyChallenge == null || captchaurl == null) { throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT); }

                String code = "http://api.solvemedia.com" + captchaurl;
                if (!skipcaptcha) {
                    final File captchaFile = this.getLocalCaptchaFile();
                    Browser.download(captchaFile, br.cloneBrowser().openGetConnection(code));
                    try {
                        ImageIO.write(ImageIO.read(captchaFile), "jpg", captchaFile);
                    } catch (final Throwable e) {
                    }
                    code = getCaptchaCode(captchaFile, downloadLink);
                } else {
                    URLConnectionAdapter con = null;
                    try {
                        con = br2.openGetConnection(code);
                    } finally {
                        try {
                            con.disconnect();
                        } catch (final Throwable e) {
                        }
                    }
                    code = "";
                }
                dlForm.put("adcopy_challenge", adcopyChallenge);
                dlForm.put("adcopy_response", code);
            }
            /* Captcha END */
            if (password) {
                passCode = handlePassword(passCode, dlForm, downloadLink);
            }
            if (!skipWaittime) {
                waitTime(timeBefore, downloadLink);
            }
            br.submitForm(dlForm);
            logger.info("Submitted DLForm");
            doSomething();
            checkErrors(downloadLink, true, passCode);
            dllink = getDllink();
            if (dllink == null) {
                logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        logger.info("Final downloadlink = " + dllink + " starting the download...");
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resumable, maxchunks);
        if (dl.getConnection().getContentType().contains("html")) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection();
            doSomething();
            checkServerErrors();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (getLinkWithoutLogin) {
            downloadLink.setProperty("freelink", dllink);
        } else {
            downloadLink.setProperty("freelink2", dllink);
        }
        if (passCode != null) {
            downloadLink.setProperty("pass", passCode);
        }
        dl.startDownload();
    }

    /** This removes fake messages which can kill the plugin */
    public void doSomething() throws NumberFormatException, PluginException {
        BRBEFORE = br.toString();
        final ArrayList<String> someStuff = new ArrayList<String>();
        final ArrayList<String> regexStuff = new ArrayList<String>();
        regexStuff.add("<\\!(\\-\\-.*?\\-\\-)>");
        regexStuff.add("(display: none;\">.*?</div>)");
        regexStuff.add("(visibility:hidden>.*?<)");
        for (final String aRegex : regexStuff) {
            final String lolz[] = br.getRegex(aRegex).getColumn(0);
            if (lolz != null) {
                for (final String dingdang : lolz) {
                    someStuff.add(dingdang);
                }
            }
        }
        for (final String fun : someStuff) {
            BRBEFORE = BRBEFORE.replace(fun, "");
        }
    }

    // XfileSharingProBasic Version 2.5.2.0
    /**
     * This is only for developers to easily implement hosters using the
     * "xfileshare(pro)" script (more informations can be found on
     * xfilesharing.net)!
     */
    @Override
    public String getAGBLink() {
        return COOKIE_HOST + "/tos.html";
    }

    public String getDllink() {
        String dllink = br.getRedirectLocation();
        if (dllink == null) {
            dllink = new Regex(BRBEFORE, "dotted #bbb;padding.*?<a href=\"(.*?)\"").getMatch(0);
            if (dllink == null) {
                dllink = new Regex(BRBEFORE, "This (direct link|download link) will be available for your IP.*?href=\"(http.*?)\"").getMatch(1);
                if (dllink == null) {
                    dllink = new Regex(BRBEFORE, "Download: <a href=\"(.*?)\"").getMatch(0);
                    if (dllink == null) {
                        final String cryptedScripts[] = br.getRegex("p\\}\\((.*?)\\.split\\('\\|'\\)").getColumn(0);
                        if (cryptedScripts != null && cryptedScripts.length != 0) {
                            for (final String crypted : cryptedScripts) {
                                dllink = decodeDownloadLink(crypted);
                                if (dllink != null) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return dllink;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        doFree(downloadLink, true, -2, true);
    }

    public String handlePassword(String passCode, final Form pwform, final DownloadLink thelink) throws IOException, PluginException {
        passCode = thelink.getStringProperty("pass", null);
        if (passCode == null) {
            passCode = Plugin.getUserInput("Password?", thelink);
        }
        pwform.put("password", passCode);
        logger.info("Put password \"" + passCode + "\" entered by user in the DLForm.");
        return Encoding.urlEncode(passCode);
    }

    // do not add @Override here to keep 0.* compatibility
    @Override
    public boolean hasAutoCaptcha() {
        return true;
    }

    // do not add @Override here to keep 0.* compatibility
    @Override
    public boolean hasCaptcha() {
        return true;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        setBrowserExclusive();
        br.setFollowRedirects(false);
        br.setCookie(COOKIE_HOST, "lang", "english");
        br.getPage(link.getDownloadURL());
        doSomething();
        if (new Regex(BRBEFORE, Pattern.compile("(No such file|>File Not Found<|>The file was removed by|Reason (of|for) deletion:\n)", Pattern.CASE_INSENSITIVE)).matches()) { throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND); }
        if (BRBEFORE.contains(MAINTENANCE)) {
            link.getLinkStatus().setStatusText(JDL.L("plugins.hoster.xfilesharingprobasic.undermaintenance", MAINTENANCEUSERTEXT));
            return AvailableStatus.TRUE;
        }
        final Regex fileInfo = new Regex(BRBEFORE, "<div class=\"DvdText\" style=\"overflow:hidden;width:500px;\">(.*?)<br />[\t\n\r ]+<span>(.*?) \\| Uploaded on");
        String filename = new Regex(BRBEFORE, "You have requested.*?https?://(www\\.)?" + COOKIE_HOST.replaceAll("https?://", "") + "/[a-z0-9]{12}/(.*?)</font>").getMatch(1);
        if (filename == null) {
            filename = new Regex(BRBEFORE, "fname\"( type=\"hidden\")? value=\"(.*?)\"").getMatch(1);
            if (filename == null) {
                filename = new Regex(BRBEFORE, "<h2>Download File(.*?)</h2>").getMatch(0);
                if (filename == null) {
                    filename = new Regex(BRBEFORE, "Filename:</b></td><td[ ]{0,2}>(.*?)</td>").getMatch(0);
                    if (filename == null) {
                        filename = new Regex(BRBEFORE, "Filename.*?nowrap.*?>(.*?)</td").getMatch(0);
                        if (filename == null) {
                            filename = new Regex(BRBEFORE, "File Name.*?nowrap>(.*?)</td").getMatch(0);
                            if (filename == null) {
                                filename = fileInfo.getMatch(0);
                                if (filename == null) {
                                    filename = new Regex(BRBEFORE, "<title>GrooveFile  ::  (.*?)</title>").getMatch(0);
                                }
                            }
                        }
                    }
                }
            }
        }
        String filesize = new Regex(BRBEFORE, "\\(([0-9]+ bytes)\\)").getMatch(0);
        if (filesize == null) {
            filesize = new Regex(BRBEFORE, "<small>\\((.*?)\\)</small>").getMatch(0);
            if (filesize == null) {
                filesize = new Regex(BRBEFORE, "</font>[ ]+\\((.*?)\\)(.*?)</font>").getMatch(0);
                if (filesize == null) {
                    filesize = fileInfo.getMatch(1);
                }
            }
        }
        if (filename == null || filename.equals("")) {
            if (BRBEFORE.contains("You have reached the download-limit")) {
                logger.warning("Waittime detected, please reconnect to make the linkchecker work!");
                return AvailableStatus.UNCHECKABLE;
            }
            logger.warning("The filename equals null, throwing \"plugin defect\" now...");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        filename = filename.replaceAll("(</b>|<b>|\\.html)", "");
        link.setFinalFileName(filename.trim());
        if (filesize != null && !filesize.equals("")) {
            link.setDownloadSize(SizeFormatter.getSize(filesize));
        }
        return AvailableStatus.TRUE;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
    }

    private void setConfigElements() {
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), "SKIP_CAPTCHA", "skip captcha").setDefaultValue(true));
        getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), "SKIP_RECAPTCHA_WAITTIME", "skip recaptcha waittime").setDefaultValue(false));
    }

    private void waitTime(final long timeBefore, final DownloadLink downloadLink) throws PluginException {
        final int passedTime = (int) ((System.currentTimeMillis() - timeBefore) / 1000) - 1;
        /** Ticket Time */
        String ttt = new Regex(BRBEFORE, "countdown\">.*?(\\d+).*?</span>").getMatch(0);
        if (ttt == null) {
            ttt = new Regex(BRBEFORE, "id=\"countdown_str\".*?<span id=\".*?\">.*?(\\d+).*?</span").getMatch(0);
        }
        if (ttt != null) {
            int tt = Integer.parseInt(ttt);
            tt -= passedTime;
            logger.info("Waittime detected, waiting " + ttt + " - " + passedTime + " seconds from now on...");
            if (tt > 0) {
                sleep(tt * 1000l, downloadLink);
            }
        }
    }

}