//=============================================================================
//===	Copyright (C) 2001-2007 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This program is free software; you can redistribute it and/or modify
//===	it under the terms of the GNU General Public License as published by
//===	the Free Software Foundation; either version 2 of the License, or (at
//===	your option) any later version.
//===
//===	This program is distributed in the hope that it will be useful, but
//===	WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===	General Public License for more details.
//===
//===	You should have received a copy of the GNU General Public License
//===	along with this program; if not, write to the Free Software
//===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: geonetwork@osgeo.org
//==============================================================================

package org.fao.geonet.kernel.harvest.harvester;

import jeeves.server.UserSession;
import jeeves.server.context.ServiceContext;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.DailyRollingFileAppender;
import org.apache.log4j.PatternLayout;
import org.fao.geonet.Logger;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.csw.common.exceptions.InvalidParameterValueEx;
import org.fao.geonet.domain.*;
import org.fao.geonet.exceptions.BadInputEx;
import org.fao.geonet.exceptions.BadParameterEx;
import org.fao.geonet.exceptions.JeevesException;
import org.fao.geonet.exceptions.OperationAbortedEx;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.MetadataIndexerProcessor;
import org.fao.geonet.kernel.harvest.Common.OperResult;
import org.fao.geonet.kernel.harvest.Common.Status;
import org.fao.geonet.kernel.setting.HarvesterSettingsManager;
import org.fao.geonet.repository.*;
import org.fao.geonet.repository.specification.MetadataSpecs;
import org.fao.geonet.resources.Resources;
import org.fao.geonet.services.harvesting.notifier.SendNotification;
import org.fao.geonet.utils.Log;
import org.fao.geonet.utils.QuartzSchedulerUtils;
import org.jdom.Element;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.quartz.JobKey.jobKey;

/**
 * Represents a harvester job. Used to launch harvester workers.
 */
public abstract class AbstractHarvester<T extends HarvestResult> {

    private static final String SCHEDULER_ID = "abstractHarvester";
    public static final String HARVESTER_GROUP_NAME = "HARVESTER_GROUP_NAME";

    //---------------------------------------------------------------------------
    //---
    //--- Static API methods
    //---
    //---------------------------------------------------------------------------


    /**
     * TODO javadoc.
     *
     * @param type
     * @param context
     * @return
     */
    public static AbstractHarvester<?> create(String type, ServiceContext context) throws BadParameterEx, OperationAbortedEx {
        //--- raises an exception if type is null
        if (type == null) {
            throw new BadParameterEx("type", null);
        }

        try {
            AbstractHarvester ah = context.getApplicationContext().getBean(type, AbstractHarvester.class);
            ah.setContext(context);

            return ah;
        } catch (Exception e) {
            throw new OperationAbortedEx("Cannot instantiate harvester", e);
        }
    }

    /**
     * This method has to be public for CGLib proxy.
     *
     * @param context set the harvester's context.
     */
    protected void setContext(ServiceContext context) {
        this.context = context;
    }

    /**
     * For the log name
     */
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmm");

    private String initializeLog() {

        // configure personalized logger
        String packagename = getClass().getPackage().getName();
        String[] packages = packagename.split("\\.");
        String packageType = packages[packages.length - 1];
        log = Log.createLogger(this.getParams().name,
                "geonetwork.harvester");

        String directory = log.getFileAppender();
        if (directory.isEmpty()) {
            directory = "./";
        }
        File d = new File(directory);
        if (!d.isDirectory()) {
            directory = d.getParent() + File.separator;
        }

        DailyRollingFileAppender fa = new DailyRollingFileAppender();
        fa.setName(this.getParams().name);
        String logfile = directory + "harvester_" + packageType + "_"
                         + this.getParams().name + "_"
                         + dateFormat.format(new Date(System.currentTimeMillis()))
                         + ".log";
        fa.setFile(logfile);
        fa.setLayout(new PatternLayout("%d{ISO8601} %-5p [%c] - %m%n"));
        fa.setThreshold(log.getThreshold());
        fa.setAppend(true);
        fa.activateOptions();

        log.setAppender(fa);

        return logfile;
    }
    //--------------------------------------------------------------------------
    //---
    //--- API methods
    //---
    //--------------------------------------------------------------------------

    /**
     * TODO javadoc.
     *
     * @param node
     * @throws BadInputEx
     * @throws SQLException
     */
    @Transactional
    public synchronized void add(Element node) throws BadInputEx, SQLException {
        status = Status.INACTIVE;
        error = null;
        id = doAdd(node);
    }

    @Transactional
    public synchronized void init(Element node, ServiceContext context) throws BadInputEx, SchedulerException {
        id = node.getAttributeValue("id");
        status = Status.parse(node.getChild("options").getChildText("status"));
        error = null;
        this.context = context;

        //--- init harvester
        doInit(node, context);

        initializeLog();
        if (status == Status.ACTIVE) {
            doSchedule();
        }
    }

    /**
     * TODO Javadoc.
     *
     * @throws SchedulerException
     */
    private void doSchedule() throws SchedulerException {
        Scheduler scheduler = getScheduler();

        JobDetail jobDetail = getParams().getJob();
        Trigger trigger = getParams().getTrigger();
        scheduler.scheduleJob(jobDetail, trigger);
    }

    /**
     * TODO Javadoc.
     *
     * @throws SchedulerException
     */
    private void doUnschedule() throws SchedulerException {
        getScheduler().deleteJob(jobKey(getParams().uuid, HARVESTER_GROUP_NAME));
    }

    /**
     * TODO Javadoc.
     *
     * @return
     * @throws SchedulerException
     */
    public static Scheduler getScheduler() throws SchedulerException {
        return QuartzSchedulerUtils.getScheduler(SCHEDULER_ID, true);
    }

    /**
     * Called when the application is shutdown.
     *
     * @throws SchedulerException
     */
    public void shutdown() throws SchedulerException {
        getScheduler().deleteJob(jobKey(getParams().uuid, HARVESTER_GROUP_NAME));
    }

    /**
     * TODO Javadoc.
     *
     * @throws SchedulerException
     */
    public static void shutdownScheduler() throws SchedulerException {
        getScheduler().shutdown(false);
    }

    /**
     * Called when the harvesting entry is removed from the system. It is used to remove harvested metadata.
     *
     * @throws Exception
     */
    @Transactional
    public synchronized void destroy() throws Exception {
        doUnschedule();

        final MetadataRepository metadataRepository = context.getBean(MetadataRepository.class);
        final SourceRepository sourceRepository = context.getBean(SourceRepository.class);
        
        final Specifications<Metadata> ownedByHarvester = Specifications.where(MetadataSpecs.hasHarvesterUuid(getParams().uuid));
        Set<String> sources = new HashSet<String>();
        for (Integer id : metadataRepository.findAllIdsBy(ownedByHarvester)) {
            sources.add(metadataRepository.findOne(id).getSourceInfo().getSourceId());
            dataMan.deleteMetadata(context, "" + id);
        }
        
        // Remove all sources related to the harvestUuid if they are not linked to any record anymore
        for (String sourceUuid : sources) {
            Long ownedBySource = 
                    metadataRepository.count(Specifications.where(MetadataSpecs.hasSource(sourceUuid)));
            if (ownedBySource == 0 && !sourceUuid.equals(params.uuid)) {
                removeIcon(sourceUuid);
                sourceRepository.delete(sourceUuid);
            }
        }

        doDestroy();
    }

    /**
     * Set harvester status to {@link Status#ACTIVE} and schedule the harvester to be ran
     * at the next time according to the harvesters schedule.
     *
     * @return return {@link OperResult#ALREADY_ACTIVE} if the harvester is already active or {@link OperResult#OK}
     * @throws SQLException
     * @throws SchedulerException
     */
    @Transactional
    public synchronized OperResult start() throws SQLException, SchedulerException {
        if (status != Status.INACTIVE) {
            return OperResult.ALREADY_ACTIVE;
        }
        settingMan.setValue("harvesting/id:" + id + "/options/status", Status.ACTIVE);

        status = Status.ACTIVE;
        error = null;

        doSchedule();

        return OperResult.OK;
    }

    /**
     * Set the harvester status to {@link Status#ACTIVE} and unschedule any scheduled jobs.
     *
     * @return {@link OperResult#ALREADY_INACTIVE} if the not currently enabled or {@link OperResult#OK}
     * @throws SQLException
     * @throws SchedulerException
     */
    @Transactional
    public synchronized OperResult stop() throws SQLException, SchedulerException {
        if (status != Status.ACTIVE) {
            return OperResult.ALREADY_INACTIVE;
        }
        settingMan.setValue("harvesting/id:" + id + "/options/status", Status.INACTIVE);
        doUnschedule();
        status = Status.INACTIVE;
        return OperResult.OK;
    }

    /**
     * Call {@link #start()} if status is currently {@link Status#INACTIVE}.  Trigger a harvester job to run immediately.
     *
     * @return {@link OperResult#OK} or {@link OperResult#ALREADY_RUNNING} if harvester is currently running.
     */
    @Transactional
    public synchronized OperResult run() throws SQLException, SchedulerException {
        if (status == Status.INACTIVE) {
            start();
        }
        if (running) {
            return OperResult.ALREADY_RUNNING;
        }
        getScheduler().triggerJob(jobKey(getParams().uuid, HARVESTER_GROUP_NAME));
        return OperResult.OK;
    }

    /**
     * Run the harvester in the synchronously (in the current thread) and return whether the harvest correctly completed.
     *
     * @return {@link OperResult#OK} or {@link OperResult#ERROR}
     */
    @Transactional
    public synchronized OperResult invoke() {
        Status oldStatus = status;

        try {
            status = Status.ACTIVE;
            return harvest();
        } finally {
            status = oldStatus;
        }
    }

    /**
     * TODO Javadoc.
     *
     * @param node
     * @throws BadInputEx
     * @throws SQLException
     * @throws SchedulerException
     */
    @Transactional
    public synchronized void update(Element node) throws BadInputEx, SQLException, SchedulerException {
        doUpdate(id, node);

        if (status == Status.ACTIVE) {
            //--- stop executor
            doUnschedule();
            //--- restart executor
            error = null;
            doSchedule();
        }
    }

    /**
     * TODO Javadoc.
     *
     * @return
     */
    public String getID() {
        return id;
    }

    /**
     * Adds harvesting result information to each harvesting entry.
     *
     * @param node
     */
    @Transactional
    public synchronized void addInfo(Element node) {
        Element info = node.getChild("info");

        //--- 'running'
        info.addContent(new Element("running").setText(running + ""));

        //--- harvester specific info
        doAddInfo(node);

        //--- add error information
        if (error != null) {
            node.addContent(JeevesException.toElement(error));
        }
    }

    /**
     * Adds harvesting information to each metadata element. Some sites can generate url for thumbnails.
     *
     * @param info
     * @param id
     * @param uuid
     */
    public void addHarvestInfo(Element info, String id, String uuid) {
        info.addContent(new Element("type").setText(getType()));
    }

    //---------------------------------------------------------------------------
    //---
    //--- Package methods (called by Executor)
    //---
    //---------------------------------------------------------------------------

    /**
     * Nested class to handle harvesting with fast indexing.
     */
    public class HarvestWithIndexProcessor extends MetadataIndexerProcessor {
        Logger logger;

        /**
         * @param dm
         * @param logger
         */
        public HarvestWithIndexProcessor(DataManager dm, Logger logger) {
            super(dm);
            this.logger = logger;
        }

        /**
         * @throws Exception
         */
        @Override
        public void process() throws Exception {
            doHarvest(logger);
        }
    }

    /**
     * Create a session for the user who created the harvester. The owner identifier is added when the harvester config
     * is created or updated according to user session.
     */
    private void login() throws Exception {

        String ownerId = getParams().ownerId;
        if (log.isDebugEnabled()) {
            log.debug("AbstractHarvester login: ownerId = " + ownerId);
        }
        
        UserRepository repository = this.context.getBean(UserRepository.class);
        User user = null;
        if (ownerId != null) {
            user = repository.findOne(ownerId);
        }
        
        // for harvesters created before owner was added to the harvester code,
        // or harvesters belonging to a user that no longer exists
        if (user == null || StringUtils.isEmpty(ownerId) || !this.dataMan.existsUser(this.context, Integer.parseInt(ownerId))) {
            // just pick any Administrator (they can all see all harvesters and groups anyway)
            user = repository.findAllByProfile(Profile.Administrator).get(0);
            getParams().ownerId = String.valueOf(user.getId());
            if (log.isDebugEnabled()) {
                log.debug("AbstractHarvester login: picked Administrator  " + ownerId + " to run this job");
            }
        }

        // todo reject if < useradmin ?

        UserSession session = new UserSession();
        session.loginAs(user);
        this.context.setUserSession(session);

        this.context.setIpAddress(null);
    }

    /**
     * Run the harvest process.
     * This has to be protected or better for CGLib to proxy to it./
     */
    @Transactional
    protected synchronized OperResult harvest() {
        OperResult operResult = OperResult.OK;
        running = true;
        try {
            long startTime = System.currentTimeMillis();

            String logfile = initializeLog();
            this.log.info("Starting harvesting of " + this.getParams().name);
            error = null;
            errors.clear();
            final Logger logger = Log.createLogger(Geonet.HARVESTER);
            final String nodeName = getParams().name + " (" + getClass().getSimpleName() + ")";
            final String lastRun = new DateTime().withZone(DateTimeZone.forID("UTC")).toString();
            try {
                login();

                //--- update lastRun
                settingMan.setValue("harvesting/id:" + id + "/info/lastRun", lastRun);

                //--- proper harvesting
                logger.info("Started harvesting from node : " + nodeName);
                HarvestWithIndexProcessor h = new HarvestWithIndexProcessor(dataMan, logger);
                // todo check (was: processwithfastindexing)
                h.process();
                logger.info("Ended harvesting from node : " + nodeName);

                if (getParams().oneRunOnly) {
                    stop();
                }
            } catch (InvalidParameterValueEx e) {
                logger.error("The harvester " + this.getParams().name + "["
                             + this.getType()
                             + "] didn't accept some of the parameters sent.");

                errors.add(new HarvestError(e, logger));
                error = e;
                operResult = OperResult.ERROR;
            } catch (Throwable t) {
                operResult = OperResult.ERROR;
                logger.warning("Raised exception while harvesting from : " + nodeName);
                logger.warning(" (C) Class   : " + t.getClass().getSimpleName());
                logger.warning(" (C) Message : " + t.getMessage());
                error = t;
                t.printStackTrace();
                errors.add(new HarvestError(t, logger));
            } finally {
                List<HarvestError> harvesterErrors = getErrors();
                if (harvesterErrors != null) {
                    errors.addAll(harvesterErrors);
                }
            }

            long elapsedTime = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startTime);

            logHarvest(logfile, logger, nodeName, lastRun, elapsedTime);
        } finally {
            running = false;
        }

        return operResult;
    }

    private void logHarvest(String logfile, Logger logger, String nodeName, String lastRun, long elapsedTime) {
        try {
            // record the results/errors for this harvest in the database
            Element result = getResult();
            if (error != null) {
                result = JeevesException.toElement(error);
            }
            Element logfile_ = new Element("logfile");
            logfile_.setText(logfile);
            result.addContent(logfile_);

            result.addContent(toElement(errors));
            final HarvestHistoryRepository historyRepository = context.getBean(HarvestHistoryRepository.class);
            final HarvestHistory history = new HarvestHistory()
                    .setHarvesterType(getType())
                    .setHarvesterName(getParams().name)
                    .setHarvesterUuid(getParams().uuid)
                    .setElapsedTime((int) elapsedTime)
                    .setHarvestDate(new ISODate(lastRun))
                    .setParams(getParams().node);
            historyRepository.save(history);


            //Send notification email, if needed
            try {
                SendNotification.process(context, history.asXml(), this);
            } catch (Exception e2) {
                logger.error("Raised exception while attempting to send email");
                logger.error(" (C) Exc   : " + e2);
                e2.printStackTrace();
            }

        } catch (Exception e) {
            logger.warning("Raised exception while attempting to store harvest history from : " + nodeName);
            e.printStackTrace();
            logger.warning(" (C) Exc   : " + e);
        }
    }


    /**
     * Convert {@link HarvestError} to an element that can be saved on the
     * database.
     *
     * @param errors
     * @return
     */
    private Element toElement(List<HarvestError> errors) {
        Element res = new Element("errors");
        for (HarvestError error : errors) {
            Element herror = new Element("error");

            Element desc = new Element("description");
            desc.setText(error.getDescription());
            herror.addContent(desc);

            Element hint = new Element("hint");
            hint.setText(error.getHint());
            herror.addContent(hint);

            herror.addContent(JeevesException.toElement(error.getOrigin()));
            res.addContent(herror);
        }
        return res;
    }
    //---------------------------------------------------------------------------
    //---
    //--- Abstract methods that must be overridden
    //---
    //---------------------------------------------------------------------------

    /**
     * Should be overriden to get a better insight on harvesting
     * <p/>
     * Returns the list of exceptions that ocurred during the harvesting but
     * didn't really stop and abort the harvest.
     *
     * @return
     */
    public synchronized List<HarvestError> getErrors() {
       return errors;
    }

    /**
     * @return
     */
    public final String getType() {
        final String[] types = context.getApplicationContext().getBeanNamesForType(getClass());
        return types[0];
    }

    /**
     * @return
     */
    public synchronized AbstractParams getParams() {
        return params;
    }

    /**
     * @param entry
     * @param context
     * @throws BadInputEx
     */
    protected abstract void doInit(Element entry, ServiceContext context) throws BadInputEx;

    /**
     * @throws SQLException
     */
    protected void doDestroy() throws SQLException {
        removeIcon(getParams().uuid);

        context.getBean(SourceRepository.class).delete(getParams().uuid);
        // FIXME: Should also delete the categories we have created for servers
    }

    private void removeIcon(String uuid) {
        File icon = new File(Resources.locateLogosDir(context), uuid+ ".gif");

        if (!icon.delete() && icon.exists()) {
            Log.warning(Geonet.HARVESTER + "." + getType(), "Unable to delete icon: " + icon);
        }
    }

    /**
     * @param node
     * @return
     * @throws BadInputEx
     * @throws SQLException
     */
    protected abstract String doAdd(Element node) throws BadInputEx, SQLException;

    /**
     * @param id
     * @param node
     * @throws BadInputEx
     * @throws SQLException
     */
    protected abstract void doUpdate(String id, Element node) throws BadInputEx, SQLException;

    /**
     * @param node
     */
    protected void doAddInfo(Element node) {
        //--- if the harvesting is not started yet, we don't have any info

        if (result == null) {
            return;
        }

        //--- ok, add proper info

        Element info = node.getChild("info");
        Element res = getResult();
        info.addContent(res);
    }

    /**
     * Extend to do the actual harvesting.
     *
     * @param l
     * @throws Exception
     */
    protected abstract void doHarvest(Logger l) throws Exception;

    //---------------------------------------------------------------------------
    //---
    //--- Protected storage methods
    //---
    //---------------------------------------------------------------------------

    /**
     * Invoked from doAdd and doUpdate in sub class implementations.
     *
     * @param params
     * @param path
     * @throws SQLException
     */
    protected void storeNode(AbstractParams params, String path) throws SQLException {
        String siteId = settingMan.add(path, "site", "");
        String optionsId = settingMan.add(path, "options", "");
        String infoId = settingMan.add(path, "info", "");
        String contentId = settingMan.add(path, "content", "");

        //--- setup site node ----------------------------------------

        settingMan.add("id:" + siteId, "name", params.name);
        settingMan.add("id:" + siteId, "uuid", params.uuid);

        /**
         * User who created or updated this node.
         */
        settingMan.add("id:" + siteId, "ownerId", params.ownerId);
        /**
         * Group selected by user who created or updated this node.
         */
        settingMan.add("id:" + siteId, "ownerGroup", params.ownerIdGroup);

        String useAccId = settingMan.add("id:" + siteId, "useAccount", params.useAccount);

        settingMan.add("id:" + useAccId, "username", params.username);
        settingMan.add("id:" + useAccId, "password", params.password);

        //--- setup options node ---------------------------------------

        settingMan.add("id:" + optionsId, "every", params.every);
        settingMan.add("id:" + optionsId, "oneRunOnly", params.oneRunOnly);
        settingMan.add("id:" + optionsId, "status", status);

        //--- setup content node ---------------------------------------

        settingMan.add("id:" + contentId, "importxslt", params.importXslt);
        settingMan.add("id:" + contentId, "validate", params.validate);

        //--- setup stats node ----------------------------------------

        settingMan.add("id:" + infoId, "lastRun", "");

        //--- store privileges and categories ------------------------

        storePrivileges(params, path);
        storeCategories(params, path);

        storeNodeExtra(params, path, siteId, optionsId);
    }

    /**
     * Override this method with an empty body to avoid privileges storage.
     *
     * @param params
     * @param path
     * @throws SQLException
     */
    protected void storePrivileges(AbstractParams params, String path) throws SQLException {
        String privId = settingMan.add(path, "privileges", "");

        for (Privileges p : params.getPrivileges()) {
            String groupId = settingMan.add("id:" + privId, "group", p.getGroupId());
            for (int oper : p.getOperations()) {
                settingMan.add("id:" + groupId, "operation", oper);
            }
        }
    }

    /**
     * Override this method with an empty body to avoid categories storage.
     *
     * @param params
     * @param path
     * @throws SQLException
     */
    protected void storeCategories(AbstractParams params, String path) throws SQLException {
        String categId = settingMan.add(path, "categories", "");

        for (String id : params.getCategories()) {
            settingMan.add("id:" + categId, "category", id);
        }
    }

    /**
     * Override this method to store harvesting node's specific settings.
     *
     * @param params
     * @param path
     * @param siteId
     * @param optionsId
     * @throws SQLException
     */
    protected void storeNodeExtra(AbstractParams params, String path, String siteId, String optionsId) throws SQLException {
    }

    /**
     * @param values
     * @param path
     * @param el
     * @param name
     */
    protected void setValue(Map<String, Object> values, String path, Element el, String name) {
        if (el == null) {
            return;
        }

        String value = el.getChildText(name);

        if (value != null) {
            values.put(path, value);
        }
    }

    /**
     * @param el
     * @param name
     * @param value
     */
    protected void add(Element el, String name, int value) {
        el.addContent(new Element(name).setText(Integer.toString(value)));
    }

    public synchronized void setParams(AbstractParams params) {
        this.params = params;
    }

    /**
     * Get the results of the last harvest.
     *
     * @return
     */
    public Element getResult() {
        Element res = new Element("result");
        if (result != null) {
            add(res, "added", result.addedMetadata);
            add(res, "atomicDatasetRecords", result.atomicDatasetRecords);
            add(res, "badFormat", result.badFormat);
            add(res, "collectionDatasetRecords", result.collectionDatasetRecords);
            add(res, "datasetUuidExist", result.datasetUuidExist);
            add(res, "doesNotValidate", result.doesNotValidate);
            add(res, "duplicatedResource", result.duplicatedResource);
            add(res, "fragmentsMatched", result.fragmentsMatched);
            add(res, "fragmentsReturned", result.fragmentsReturned);
            add(res, "fragmentsUnknownSchema", result.fragmentsUnknownSchema);
            add(res, "incompatible", result.incompatibleMetadata);
            add(res, "recordsBuilt", result.recordsBuilt);
            add(res, "recordsUpdated", result.recordsUpdated);
            add(res, "removed", result.locallyRemoved);
            add(res, "serviceRecords", result.serviceRecords);
            add(res, "subtemplatesAdded", result.subtemplatesAdded);
            add(res, "subtemplatesRemoved", result.subtemplatesRemoved);
            add(res, "subtemplatesUpdated", result.subtemplatesUpdated);
            add(res, "total", result.totalMetadata);
            add(res, "unchanged", result.unchangedMetadata);
            add(res, "unknownSchema", result.unknownSchema);
            add(res, "unretrievable", result.unretrievable);
            add(res, "updated", result.updatedMetadata);
            add(res, "thumbnails", result.thumbnails);
            add(res, "thumbnailsFailed", result.thumbnailsFailed);
        }
        return res;
    }

    /**
     * Get the list of registered harvester
     *
     * @param context
     * @return
     */
    public static String[] getHarvesterTypes(ServiceContext context) {
        return context.getApplicationContext().getBeanNamesForType(AbstractHarvester.class);
    }

    /**
     * Who should we notify by default?
     *
     * @return
     * @throws Exception
     */
    @Transactional
    public String getOwnerEmail() throws Exception {
        String ownerId = getParams().ownerIdGroup;

        final Group group = context.getBean(GroupRepository.class).findOne(Integer.parseInt(ownerId));
        return group.getEmail();
    }

    //--------------------------------------------------------------------------
    //---
    //--- Variables
    //---
    //--------------------------------------------------------------------------
    private String id;
    private volatile Status status;
    /**
     * Exception that aborted the harvesting
     */
    private Throwable error;
    /**
     * Contains all the warnings and errors that didn't abort the execution, but were thrown during harvesting
     */
    private List<HarvestError> errors = new LinkedList<HarvestError>();
    private boolean running = false;


    protected ServiceContext context;
    @Autowired
    protected HarvesterSettingsManager settingMan;
    @Autowired
    protected DataManager dataMan;

    protected AbstractParams params;
    protected T result;

    protected Logger log = Log.createLogger(Geonet.HARVESTER);
}
