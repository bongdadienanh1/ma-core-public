/*
    Copyright (C) 2014 Infinite Automation Systems Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.serotonin.m2m2.db.dao;

import static com.serotonin.m2m2.db.dao.DataPointTagsDao.DATA_POINT_TAGS_PIVOT_ALIAS;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.jooq.SelectOnConditionStep;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;

import com.infiniteautomation.mango.db.query.ConditionSortLimit;
import com.infiniteautomation.mango.db.query.ConditionSortLimitWithTagKeys;
import com.infiniteautomation.mango.db.query.Index;
import com.infiniteautomation.mango.db.query.JoinClause;
import com.infiniteautomation.mango.db.query.QueryAttribute;
import com.infiniteautomation.mango.db.query.RQLToCondition;
import com.infiniteautomation.mango.db.query.RQLToConditionWithTagKeys;
import com.infiniteautomation.mango.db.query.RQLToSQLSelect;
import com.infiniteautomation.mango.db.query.SQLStatement;
import com.infiniteautomation.mango.spring.events.DaoEvent;
import com.infiniteautomation.mango.spring.events.DaoEventType;
import com.infiniteautomation.mango.spring.events.DataPointTagsUpdatedEvent;
import com.infiniteautomation.mango.util.LazyInitSupplier;
import com.infiniteautomation.mango.util.usage.DataPointUsageStatistics;
import com.serotonin.ModuleNotLoadedException;
import com.serotonin.ShouldNeverHappenException;
import com.serotonin.db.MappedRowCallback;
import com.serotonin.db.pair.IntStringPair;
import com.serotonin.db.spring.ExtendedJdbcTemplate;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.IMangoLifecycle;
import com.serotonin.m2m2.LicenseViolatedException;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.module.DataPointChangeDefinition;
import com.serotonin.m2m2.module.ModuleRegistry;
import com.serotonin.m2m2.rt.dataImage.DataPointRT;
import com.serotonin.m2m2.rt.event.type.AuditEventType;
import com.serotonin.m2m2.rt.event.type.EventType;
import com.serotonin.m2m2.vo.DataPointExtendedNameComparator;
import com.serotonin.m2m2.vo.DataPointSummary;
import com.serotonin.m2m2.vo.DataPointVO;
import com.serotonin.m2m2.vo.IDataPoint;
import com.serotonin.m2m2.vo.User;
import com.serotonin.m2m2.vo.bean.PointHistoryCount;
import com.serotonin.m2m2.vo.comment.UserCommentVO;
import com.serotonin.m2m2.vo.dataSource.DataSourceVO;
import com.serotonin.m2m2.vo.event.detector.AbstractEventDetectorVO;
import com.serotonin.m2m2.vo.event.detector.AbstractPointEventDetectorVO;
import com.serotonin.m2m2.vo.hierarchy.PointFolder;
import com.serotonin.m2m2.vo.hierarchy.PointHierarchy;
import com.serotonin.m2m2.vo.hierarchy.PointHierarchyEventDispatcher;
import com.serotonin.m2m2.vo.permission.Permissions;
import com.serotonin.m2m2.vo.template.BaseTemplateVO;
import com.serotonin.provider.Providers;
import com.serotonin.util.SerializationHelper;

import net.jazdw.rql.parser.ASTNode;

/**
 * This class is a Half-Breed between the legacy Dao and the new type that extends AbstractDao.
 *
 * The top half of the code is the legacy code, the bottom is the new style.
 *
 * Eventually all the method innards will be reworked, leaving the names the same.
 *
 * @author Terry Packer
 *
 */
@Repository()
public class DataPointDao extends AbstractDao<DataPointVO>{
    static final Log LOG = LogFactory.getLog(DataPointDao.class);

    private static final LazyInitSupplier<DataPointDao> springInstance = new LazyInitSupplier<>(() -> {
        Object o = Common.getRuntimeContext().getBean(DataPointDao.class);
        if(o == null)
            throw new ShouldNeverHappenException("DAO not initialized in Spring Runtime Context");
        return (DataPointDao)o;
    });

    public static final Name DATA_POINTS_ALIAS = DSL.name("dp");
    public static final Table<Record> DATA_POINTS = DSL.table(DSL.name(SchemaDefinition.DATAPOINTS_TABLE)).as(DATA_POINTS_ALIAS);
    public static final Field<Integer> ID = DSL.field(DATA_POINTS_ALIAS.append("id"), SQLDataType.INTEGER.nullable(false));
    public static final Field<Integer> DATA_SOURCE_ID = DSL.field(DATA_POINTS_ALIAS.append("dataSourceId"), SQLDataType.INTEGER.nullable(false));
    public static final Field<String> READ_PERMISSION = DSL.field(DATA_POINTS_ALIAS.append("readPermission"), SQLDataType.VARCHAR(255).nullable(true));
    public static final Field<String> SET_PERMISSION = DSL.field(DATA_POINTS_ALIAS.append("setPermission"), SQLDataType.VARCHAR(255).nullable(true));

    /**
     * Private as we only ever want 1 of these guys
     */
    private DataPointDao() {
        super(EventType.EventTypeNames.DATA_POINT, "dp",
                new String[] { "ds.name", "ds.xid", "ds.dataSourceType", "template.name", "template.xid" }, //Extra Properties not in table
                false, new TranslatableMessage("internal.monitor.DATA_POINT_COUNT"));
    }

    /**
     * Get cached instance from Spring Context
     * @return
     */
    public static DataPointDao getInstance() {
        return springInstance.get();
    }

    //
    //
    // Data Points
    //
    @Override
    public String generateUniqueXid() {
        return generateUniqueXid(DataPointVO.XID_PREFIX, tableName);
    }

    public String getExtendedPointName(int dataPointId) {
        //TODO? return ejt.queryForObject("SELECT CONCAT(deviceName, ' - ', name) FROM dataPoints WHERE ID=?",
        //                                  new Object[]{dataPointId}, String.class, "?");
        DataPointVO vo = getDataPoint(dataPointId, false);
        if (vo == null)
            return "?";
        return vo.getExtendedName();
    }

    private static final String DATA_POINT_SELECT = //
            "select dp.data, dp.id, dp.xid, dp.dataSourceId, dp.name, dp.deviceName, dp.enabled, dp.pointFolderId, " //
            + "  dp.loggingType, dp.intervalLoggingPeriodType, dp.intervalLoggingPeriod, dp.intervalLoggingType, " //
            + "  dp.tolerance, dp.purgeOverride, dp.purgeType, dp.purgePeriod, dp.defaultCacheSize, " //
            + "  dp.discardExtremeValues, dp.engineeringUnits, dp.readPermission, dp.setPermission, dp.templateId, dp.rollup, "
            + "  ds.name,  ds.xid, ds.dataSourceType " //
            + "from dataPoints dp join dataSources ds on ds.id = dp.dataSourceId ";

    public List<DataPointVO> getDataPoints(Comparator<IDataPoint> comparator, boolean includeRelationalData) {
        List<DataPointVO> dps = query(DATA_POINT_SELECT, new DataPointRowMapper());
        if (includeRelationalData)
            loadPartialRelationalData(dps);
        if (comparator != null)
            Collections.sort(dps, comparator);
        return dps;
    }

    public List<DataPointVO> getDataPoints(int dataSourceId, Comparator<DataPointVO> comparator) {
        return getDataPoints(dataSourceId, comparator, true);
    }

    public List<DataPointVO> getDataPoints(int dataSourceId, Comparator<DataPointVO> comparator,
            boolean includeRelationalData) {
        List<DataPointVO> dps = query(DATA_POINT_SELECT+ " where dp.dataSourceId=?", new Object[] { dataSourceId },
                new DataPointRowMapper());
        if (includeRelationalData)
            loadPartialRelationalData(dps);
        if (comparator != null)
            Collections.sort(dps, comparator);
        return dps;
    }

    public List<DataPointVO> getDataPointsForDataSourceStart(int dataSourceId) {
        List<DataPointVO> dps = query(DataPointStartupResultSetExtractor.DATA_POINT_SELECT_STARTUP, new Object[] { dataSourceId },
                new DataPointStartupResultSetExtractor());

        return dps;
    }

    public DataPointVO getDataPoint(int id) {
        return getDataPoint(id, true);
    }

    public DataPointVO getDataPoint(int id, boolean includeRelationalData) {
        try {
            DataPointVO dp = queryForObject(DATA_POINT_SELECT + " where dp.id=?", new Object[] { id },
                    new DataPointRowMapper(), null);
            if (dp != null && includeRelationalData)
                loadRelationalData(dp);
            return dp;
        }
        catch (ShouldNeverHappenException e) {
            // If the module was removed but there are still records in the database, this exception will be
            // thrown. Check the inner exception to confirm.
            if (e.getCause() instanceof ModuleNotLoadedException) {
                // Yep. Log the occurrence and continue.
                LOG.error("Data point with id '" + id + "' could not be loaded. Is its module missing?", e.getCause());
            }else{
                LOG.error(e.getMessage(), e);
            }
        }
        return null;
    }

    public DataPointVO getDataPoint(String xid) {
        DataPointVO dp = queryForObject(DATA_POINT_SELECT + " where dp.xid=?", new Object[] { xid },
                new DataPointRowMapper(), null);
        if (dp != null) {
            loadRelationalData(dp);
        }
        return dp;
    }

    /**
     * Get all data point Ids in the table
     * @return
     */
    public List<Integer> getDataPointIds(){
        return queryForList("SELECT id FROM dataPoints" , Integer.class);
    }

    class DataPointStartupResultSetExtractor implements ResultSetExtractor<List<DataPointVO>> {
        private static final int EVENT_DETECTOR_FIRST_COLUMN = 27;
        private final EventDetectorRowMapper eventRowMapper = new EventDetectorRowMapper(EVENT_DETECTOR_FIRST_COLUMN, 5);
        static final String DATA_POINT_SELECT_STARTUP = //
                "select dp.data, dp.id, dp.xid, dp.dataSourceId, dp.name, dp.deviceName, dp.enabled, dp.pointFolderId, " //
                + "  dp.loggingType, dp.intervalLoggingPeriodType, dp.intervalLoggingPeriod, dp.intervalLoggingType, " //
                + "  dp.tolerance, dp.purgeOverride, dp.purgeType, dp.purgePeriod, dp.defaultCacheSize, " //
                + "  dp.discardExtremeValues, dp.engineeringUnits, dp.readPermission, dp.setPermission, dp.templateId, dp.rollup, ds.name, " //
                + "  ds.xid, ds.dataSourceType, ped.id, ped.xid, ped.sourceTypeName, ped.typeName, ped.data, ped.dataPointId " //
                + "  from dataPoints dp join dataSources ds on ds.id = dp.dataSourceId " //
                + "  left outer join eventDetectors ped on dp.id = ped.dataPointId where dp.dataSourceId=?";

        @Override
        public List<DataPointVO> extractData(ResultSet rs) throws SQLException, DataAccessException {
            Map<Integer, DataPointVO> result = new HashMap<Integer, DataPointVO>();
            DataPointRowMapper pointRowMapper = new DataPointRowMapper();
            while(rs.next()) {
                int id = rs.getInt(2); //dp.id column number
                if(result.containsKey(id))
                    try{
                        addEventDetector(result.get(id), rs);
                    }catch(Exception e){
                        LOG.error("Point not fully initialized: " + e.getMessage(), e);
                    }
                else {
                    DataPointVO dpvo = pointRowMapper.mapRow(rs, rs.getRow());
                    dpvo.setEventDetectors(new ArrayList<AbstractPointEventDetectorVO<?>>());
                    result.put(id, dpvo);
                    try{
                        addEventDetector(dpvo, rs);
                    }catch(Exception e){
                        LOG.error("Point not fully initialized: " + e.getMessage(), e);
                    }
                }
            }
            return new ArrayList<DataPointVO>(result.values());
        }

        private void addEventDetector(DataPointVO dpvo, ResultSet rs) throws SQLException {
            if(rs.getObject(EVENT_DETECTOR_FIRST_COLUMN) == null)
                return;
            AbstractEventDetectorVO<?> edvo = eventRowMapper.mapRow(rs, rs.getRow());
            AbstractPointEventDetectorVO<?> ped = (AbstractPointEventDetectorVO<?>) edvo;
            ped.njbSetDataPoint(dpvo);
            dpvo.getEventDetectors().add(ped);
        }
    }

    class DataPointRowMapper implements RowMapper<DataPointVO> {
        @Override
        public DataPointVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            int i = 0;

            DataPointVO dp = (DataPointVO) SerializationHelper.readObjectInContext(rs.getBinaryStream(++i));
            dp.setId(rs.getInt(++i));
            dp.setXid(rs.getString(++i));
            dp.setDataSourceId(rs.getInt(++i));
            dp.setName(rs.getString(++i));
            dp.setDeviceName(rs.getString(++i));
            dp.setEnabled(charToBool(rs.getString(++i)));
            dp.setPointFolderId(rs.getInt(++i));
            dp.setLoggingType(rs.getInt(++i));
            dp.setIntervalLoggingPeriodType(rs.getInt(++i));
            dp.setIntervalLoggingPeriod(rs.getInt(++i));
            dp.setIntervalLoggingType(rs.getInt(++i));
            dp.setTolerance(rs.getDouble(++i));
            dp.setPurgeOverride(charToBool(rs.getString(++i)));
            dp.setPurgeType(rs.getInt(++i));
            dp.setPurgePeriod(rs.getInt(++i));
            dp.setDefaultCacheSize(rs.getInt(++i));
            dp.setDiscardExtremeValues(charToBool(rs.getString(++i)));
            dp.setEngineeringUnits(rs.getInt(++i));
            dp.setReadPermission(rs.getString(++i));
            dp.setSetPermission(rs.getString(++i));
            //Because we read 0 for null
            dp.setTemplateId(rs.getInt(++i));
            if(rs.wasNull())
                dp.setTemplateId(null);
            dp.setRollup(rs.getInt(++i));

            // Data source information.
            dp.setDataSourceName(rs.getString(++i));
            dp.setDataSourceXid(rs.getString(++i));
            dp.setDataSourceTypeName(rs.getString(++i));

            dp.ensureUnitsCorrect();

            return dp;
        }
    }

    public void saveDataPoint(final DataPointVO dp) {
        if(dp.getId() == Common.NEW_ID) checkAddPoint();

        getTransactionTemplate().execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                // Decide whether to insert or update.
                if (dp.getId() == Common.NEW_ID)
                    insertDataPoint(dp);
                else
                    updateDataPoint(dp);

                // Reset the point hierarchy so that the new or changed point
                // gets reflected.
                clearPointHierarchyCache();
            }
        });
    }

    public void checkAddPoint() {
        IMangoLifecycle lifecycle = Providers.get(IMangoLifecycle.class);
        Integer limit = lifecycle.dataPointLimit();
        if(limit != null && this.countMonitor.getValue().get() >= limit) {
            String licenseType;
            if(Common.license() != null)
                licenseType = Common.license().getLicenseType();
            else
                licenseType = "Free";
            throw new LicenseViolatedException(new TranslatableMessage("license.dataPointLimit", licenseType, limit));
        }
    }

    void insertDataPoint(final DataPointVO dp) {
        for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
            def.beforeInsert(dp);

        // Create a default text renderer
        if (dp.getTextRenderer() == null)
            dp.defaultTextRenderer();

        dp.setId(ejt.doInsert(
                "insert into dataPoints (xid, dataSourceId, name, deviceName, enabled, pointFolderId, loggingType, " //
                + "intervalLoggingPeriodType, intervalLoggingPeriod, intervalLoggingType, tolerance, " //
                + "purgeOverride, purgeType, purgePeriod, defaultCacheSize, discardExtremeValues, " //
                + "engineeringUnits, readPermission, setPermission, templateId, rollup, dataTypeId, data) " //
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", //
                new Object[] { dp.getXid(), dp.getDataSourceId(), dp.getName(), dp.getDeviceName(),
                        boolToChar(dp.isEnabled()), dp.getPointFolderId(), dp.getLoggingType(),
                        dp.getIntervalLoggingPeriodType(), dp.getIntervalLoggingPeriod(), dp.getIntervalLoggingType(),
                        dp.getTolerance(), boolToChar(dp.isPurgeOverride()), dp.getPurgeType(), dp.getPurgePeriod(),
                        dp.getDefaultCacheSize(), boolToChar(dp.isDiscardExtremeValues()), dp.getEngineeringUnits(),
                        dp.getReadPermission(), dp.getSetPermission(), dp.getTemplateId(), dp.getRollup(), dp.getPointLocator().getDataTypeId(), SerializationHelper.writeObject(dp) }, //
                new int[] { Types.VARCHAR, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.CHAR, Types.INTEGER,
                        Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.DOUBLE, Types.CHAR,
                        Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.CHAR, Types.INTEGER, Types.VARCHAR,
                        Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.BINARY }));

        // Save the relational information.
        saveRelationalData(dp, true);

        this.publishEvent(new DaoEvent<DataPointVO>(this, DaoEventType.CREATE, dp, null, null));

        AuditEventType.raiseAddedEvent(AuditEventType.TYPE_DATA_POINT, dp);

        for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
            def.afterInsert(dp);
        this.countMonitor.increment();
    }

    void updateDataPoint(final DataPointVO dp) {
        for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
            def.beforeUpdate(dp);

        DataPointVO old = getDataPoint(dp.getId());

        //If have a new data type we will wipe our history
        if (old.getPointLocator().getDataTypeId() != dp.getPointLocator().getDataTypeId())
            Common.databaseProxy.newPointValueDao().deletePointValues(dp.getId());

        // Save the VO information.
        updateDataPointShallow(dp);

        this.publishEvent(new DaoEvent<DataPointVO>(this, DaoEventType.UPDATE, dp, old.getXid(), null));
        AuditEventType.raiseChangedEvent(AuditEventType.TYPE_DATA_POINT, old, dp);

        // Save the relational information.
        saveRelationalData(dp, false);

        for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
            def.afterUpdate(dp);
    }

    public void updateDataPointShallow(final DataPointVO dp) {
        ejt.update(
                "update dataPoints set xid=?, name=?, deviceName=?, enabled=?, pointFolderId=?, loggingType=?, " //
                + "intervalLoggingPeriodType=?, intervalLoggingPeriod=?, intervalLoggingType=?, " //
                + "tolerance=?, purgeOverride=?, purgeType=?, purgePeriod=?, defaultCacheSize=?, " //
                + "discardExtremeValues=?, engineeringUnits=?, readPermission=?, setPermission=?, "//
                + "templateId=?, rollup=?, dataTypeId=?, data=? where id=?", //
                new Object[] { dp.getXid(), dp.getName(), dp.getDeviceName(), boolToChar(dp.isEnabled()),
                        dp.getPointFolderId(), dp.getLoggingType(), dp.getIntervalLoggingPeriodType(),
                        dp.getIntervalLoggingPeriod(), dp.getIntervalLoggingType(), dp.getTolerance(),
                        boolToChar(dp.isPurgeOverride()), dp.getPurgeType(), dp.getPurgePeriod(),
                        dp.getDefaultCacheSize(), boolToChar(dp.isDiscardExtremeValues()), dp.getEngineeringUnits(),
                        dp.getReadPermission(), dp.getSetPermission(), dp.getTemplateId(), dp.getRollup(),
                        dp.getPointLocator().getDataTypeId(), SerializationHelper.writeObject(dp), dp.getId() }, //
                new int[] { Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.CHAR, Types.INTEGER, Types.INTEGER,
                        Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.DOUBLE, Types.CHAR, Types.INTEGER,
                        Types.INTEGER, Types.INTEGER, Types.CHAR, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.INTEGER,
                        Types.INTEGER, Types.INTEGER, Types.BINARY, Types.INTEGER });
    }

    public void saveEnabledColumn(DataPointVO dp) {
        ejt.update("UPDATE dataPoints SET enabled=? WHERE id=?", new Object[]{boolToChar(dp.isEnabled()), dp.getId()});
        this.publishEvent(new DaoEvent<DataPointVO>(this, DaoEventType.UPDATE, dp, null, null));
        AuditEventType.raiseToggleEvent(AuditEventType.TYPE_DATA_POINT, dp);
    }

    public void deleteDataPoints(final int dataSourceId) {
        List<DataPointVO> old = getDataPoints(dataSourceId, null, true);

        for (DataPointVO dp : old) {
            for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
                def.beforeDelete(dp.getId());
        }

        getTransactionTemplate().execute(new TransactionCallbackWithoutResult() {
            @SuppressWarnings("synthetic-access")
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                List<Integer> pointIds = queryForList("select id from dataPoints where dataSourceId=?",
                        new Object[] { dataSourceId }, Integer.class);
                if (pointIds.size() > 0)
                    deleteDataPointImpl(createDelimitedList(new HashSet<>(pointIds), ",", null));
            }
        });

        for (DataPointVO dp : old) {
            for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
                def.afterDelete(dp.getId());
            this.publishEvent(new DaoEvent<DataPointVO>(this, DaoEventType.DELETE, dp, null, null));
            AuditEventType.raiseDeletedEvent(AuditEventType.TYPE_DATA_POINT, dp);
            this.countMonitor.decrement();
        }
    }

    /**
     * Override the delete method to perform extra work
     */
    @Override
    public void delete(int id) {
        deleteDataPoint(id);
    }

    public void deleteDataPoint(final int dataPointId) {
        DataPointVO dp = getDataPoint(dataPointId);
        if (dp != null) {
            for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
                def.beforeDelete(dataPointId);

            getTransactionTemplate().execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    deleteDataPointImpl(dataPointId);
                }
            });

            for (DataPointChangeDefinition def : ModuleRegistry.getDefinitions(DataPointChangeDefinition.class))
                def.afterDelete(dataPointId);

            this.publishEvent(new DaoEvent<DataPointVO>(this, DaoEventType.DELETE, dp, null, null));
            AuditEventType.raiseDeletedEvent(AuditEventType.TYPE_DATA_POINT, dp);
            countMonitor.decrement();
        }
    }

    /**
     * TODO Mango 3.6 refactor to take a data source ID and use a join on data source ID
     */
    void deleteDataPointImpl(String dataPointIdList) {
        dataPointIdList = "(" + dataPointIdList + ")";
        ejt.update("delete from eventHandlersMapping where eventTypeName=? and eventTypeRef1 in " + dataPointIdList,
                new Object[] { EventType.EventTypeNames.DATA_POINT });
        ejt.update("delete from userComments where commentType=2 and typeKey in " + dataPointIdList);
        ejt.update("delete from eventDetectors where dataPointId in " + dataPointIdList);
        ejt.update("delete from dataPoints where id in " + dataPointIdList);

        clearPointHierarchyCache();
    }

    void deleteDataPointImpl(int dataPointId) {
        ejt.update("delete from eventHandlersMapping where eventTypeName=? and eventTypeRef1 = " + dataPointId,
                new Object[] { EventType.EventTypeNames.DATA_POINT });
        ejt.update("delete from userComments where commentType=2 and typeKey = " + dataPointId);
        ejt.update("delete from eventDetectors where dataPointId = " + dataPointId);
        ejt.update("delete from dataPoints where id = " + dataPointId);
        clearPointHierarchyCache();
    }

    public int countPointsForDataSourceType(String dataSourceType) {
        return ejt.queryForInt("SELECT count(DISTINCT dp.id) FROM dataPoints dp LEFT JOIN dataSources ds ON dp.dataSourceId=ds.id "
                + "WHERE ds.dataSourceType=?", new Object[] { dataSourceType }, 0);
    }

    //
    //
    // Event detectors
    //

    /**
     * This method would be more accurately named loadEventDetectors().
     * Loads the event detectors from the database and sets them on the data point.
     *
     * @param dp
     */
    public void setEventDetectors(DataPointVO dp) {
        List<AbstractEventDetectorVO<?>> detectors = EventDetectorDao.getInstance().getWithSourceId(
                EventType.EventTypeNames.DATA_POINT, dp.getId());
        List<AbstractPointEventDetectorVO<?>> peds = new ArrayList<>();
        for(AbstractEventDetectorVO<?> ed : detectors){
            AbstractPointEventDetectorVO<?> ped = (AbstractPointEventDetectorVO<?>) ed;
            ped.njbSetDataPoint(dp);
            peds.add(ped);
        }
        dp.setEventDetectors(peds);
    }

    private void saveEventDetectors(DataPointVO dp) {
        // Get the ids of the existing detectors for this point.
        final List<AbstractEventDetectorVO<?>> existingDetectors = EventDetectorDao.getInstance().getWithSourceId(
                EventType.EventTypeNames.DATA_POINT, dp.getId());

        // Insert or update each detector in the point.
        for (AbstractPointEventDetectorVO<?> ped : dp.getEventDetectors()) {
            if (ped.getId() > 0){
                //Remove from list
                removeFromList(existingDetectors, ped.getId());
            } else {
                ped.setId(Common.NEW_ID);
            }
            ped.setSourceId(dp.getId());
            EventDetectorDao.getInstance().saveFull(ped);
        }

        // Delete detectors for any remaining ids in the list of existing
        // detectors.
        for (AbstractEventDetectorVO<?> ed : existingDetectors) {
            //Set the data point so the detector can get its event type when it is deleted
            AbstractPointEventDetectorVO<?> ped = (AbstractPointEventDetectorVO<?>)ed;
            ped.njbSetDataPoint(dp);
            EventDetectorDao.getInstance().delete(ed);
        }
    }

    private AbstractEventDetectorVO<?> removeFromList(List<AbstractEventDetectorVO<?>> list, int id) {
        for (AbstractEventDetectorVO<?> ped : list) {
            if (ped.getId() == id) {
                list.remove(ped);
                return ped;
            }
        }
        return null;
    }

    //
    //
    // Point comments
    //
    private static final String POINT_COMMENT_SELECT = UserCommentDao.USER_COMMENT_SELECT
            + "where uc.commentType= " + UserCommentVO.TYPE_POINT + " and uc.typeKey=? " + "order by uc.ts";

    /**
     * This method would be more accurately named loadPointComments().
     * Loads the comments from the database and them on the data point.
     *
     * @param dp
     */
    private void setPointComments(DataPointVO dp) {
        dp.setComments(query(POINT_COMMENT_SELECT, new Object[] { dp.getId() }, UserCommentDao.getInstance().getRowMapper()));
    }


    /**
     * Get the
     *
     * @return
     */
    public List<PointHistoryCount> getTopPointHistoryCounts() {
        if (Common.databaseProxy.getNoSQLProxy() == null)
            return this.getTopPointHistoryCountsSql();
        return this.getTopPointHistoryCountsNoSql();
    }

    private List<PointHistoryCount> getTopPointHistoryCountsNoSql() {

        PointValueDao dao = Common.databaseProxy.newPointValueDao();
        //For now we will do this the slow way
        List<DataPointVO> points = getDataPoints(DataPointExtendedNameComparator.instance, false);
        List<PointHistoryCount> counts = new ArrayList<>();
        for (DataPointVO point : points) {
            PointHistoryCount phc = new PointHistoryCount();
            long count = dao.dateRangeCount(point.getId(), 0L, Long.MAX_VALUE);
            phc.setCount((int) count);
            phc.setPointId(point.getId());
            phc.setPointName(point.getName());
            counts.add(phc);
        }
        Collections.sort(counts, new Comparator<PointHistoryCount>() {

            @Override
            public int compare(PointHistoryCount count1, PointHistoryCount count2) {
                return count2.getCount() - count1.getCount();
            }

        });

        return counts;
    }

    private List<PointHistoryCount> getTopPointHistoryCountsSql() {
        List<PointHistoryCount> counts = query(
                "select dataPointId, count(*) from pointValues group by dataPointId order by 2 desc",
                new RowMapper<PointHistoryCount>() {
                    @Override
                    public PointHistoryCount mapRow(ResultSet rs, int rowNum) throws SQLException {
                        PointHistoryCount c = new PointHistoryCount();
                        c.setPointId(rs.getInt(1));
                        c.setCount(rs.getInt(2));
                        return c;
                    }
                });

        List<DataPointVO> points = getDataPoints(DataPointExtendedNameComparator.instance, false);

        // Collate in the point names.
        for (PointHistoryCount c : counts) {
            for (DataPointVO point : points) {
                if (point.getId() == c.getPointId()) {
                    c.setPointName(point.getExtendedName());
                    break;
                }
            }
        }

        // Remove the counts for which there are no point, i.e. deleted.
        Iterator<PointHistoryCount> iter = counts.iterator();
        while (iter.hasNext()) {
            PointHistoryCount c = iter.next();
            if (c.getPointName() == null)
                iter.remove();
        }

        return counts;
    }

    /**
     * Get the count of data points per type of data source
     * @return
     */
    public List<DataPointUsageStatistics> getUsage() {
        return ejt.query("SELECT ds.dataSourceType, COUNT(ds.dataSourceType) FROM dataPoints as dp LEFT JOIN dataSources ds ON dp.dataSourceId=ds.id GROUP BY ds.dataSourceType", 
                new RowMapper<DataPointUsageStatistics>() {
                @Override
                public DataPointUsageStatistics mapRow(ResultSet rs, int rowNum) throws SQLException {
                    DataPointUsageStatistics usage = new DataPointUsageStatistics();
                    usage.setDataSourceType(rs.getString(1));
                    usage.setCount(rs.getInt(2));
                    return usage;
                }
            });
    }
    
    //
    // Data point summaries
    private static final String DATA_POINT_SUMMARY_SELECT = //
            "select id, xid, dataSourceId, name, deviceName, pointFolderId, readPermission, setPermission from dataPoints ";

    public List<DataPointSummary> getDataPointSummaries(Comparator<IDataPoint> comparator) {

        List<DataPointSummary> dps = query(DATA_POINT_SUMMARY_SELECT, new ResultSetExtractor<List<DataPointSummary>>() {

            @Override
            public List<DataPointSummary> extractData(ResultSet rs) throws SQLException, DataAccessException {
                DataPointSummaryRowMapper rowMapper = new DataPointSummaryRowMapper();
                List<DataPointSummary> results = new ArrayList<>();
                int rowNum = 0;
                while (rs.next()) {
                    try {
                        results.add(rowMapper.mapRow(rs, rowNum++));
                    }
                    catch (ShouldNeverHappenException e) {
                        // If the module was removed but there are still records in the database, this exception will be
                        // thrown. Check the inner exception to confirm.
                        if (e.getCause() instanceof ModuleNotLoadedException) {
                            // Yep. Log the occurrence and continue.
                            LOG.error("Data point with id '" + rs.getInt("id") + "' and name '" + rs.getString("name")
                            + "' could not be loaded. Is its module missing?", e.getCause());
                        }else {
                            LOG.error(e.getMessage(), e);
                        }
                    }
                }
                return results;

            }

        });

        if (comparator != null)
            Collections.sort(dps, comparator);
        return dps;
    }

    class DataPointSummaryRowMapper implements RowMapper<DataPointSummary> {
        @Override
        public DataPointSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
            int i = 0;
            DataPointSummary d = new DataPointSummary();
            d.setId(rs.getInt(++i));
            d.setXid(rs.getString(++i));
            d.setDataSourceId(rs.getInt(++i));
            d.setName(rs.getString(++i));
            d.setDeviceName(rs.getString(++i));
            d.setPointFolderId(rs.getInt(++i));
            String readPermission = rs.getString(++i);
            d.setReadPermission(readPermission);
            d.setReadPermissionsSet(Permissions.explodePermissionGroups(readPermission));
            String setPermission = rs.getString(++i);
            d.setSetPermission(setPermission);
            d.setSetPermissionsSet(Permissions.explodePermissionGroups(setPermission));
            return d;
        }
    }

    //
    //
    // Point hierarchy
    //
    static PointHierarchy cachedPointHierarchy;

    /**
     * Get the point hierarchy from its cached copy.  The read only cached copy should
     * never be modified, if you intend to modify it ensure you pass readOnly=false
     *
     * @param readOnly - do not modify a read only point hierarchy
     * @return
     */
    public PointHierarchy getPointHierarchy(boolean readOnly) {

        if(cachedPointHierarchy == null) {
            final Map<Integer, List<PointFolder>> folders = new HashMap<>();

            // Get the folder list.
            ejt.query("select id, parentId, name from dataPointHierarchy order by name", new RowCallbackHandler() {
                @Override
                public void processRow(ResultSet rs) throws SQLException {
                    PointFolder f = new PointFolder(rs.getInt(1), rs.getString(3));
                    int parentId = rs.getInt(2);
                    List<PointFolder> folderList = folders.get(parentId);
                    if (folderList == null) {
                        folderList = new LinkedList<>();
                        folders.put(parentId, folderList);
                    }
                    folderList.add(f);
                }
            });

            // Create the folder hierarchy.
            PointHierarchy ph = new PointHierarchy();
            addFoldersToHeirarchy(ph, 0, folders);

            // Add data points.
            List<DataPointSummary> points = getDataPointSummaries(DataPointExtendedNameComparator.instance);
            for (DataPointSummary dp : points)
                ph.addDataPoint(dp.getPointFolderId(), dp);

            cachedPointHierarchy = ph;
        }

        if(readOnly)
            return cachedPointHierarchy;
        else
            return cachedPointHierarchy.clone();
    }

    public static void clearPointHierarchyCache() {
        cachedPointHierarchy = null;
        PointHierarchyEventDispatcher.firePointHierarchyCleared();
    }

    private void addFoldersToHeirarchy(PointHierarchy ph, int parentId, Map<Integer, List<PointFolder>> folders) {
        List<PointFolder> folderList = folders.remove(parentId);
        if (folderList == null)
            return;

        for (PointFolder f : folderList) {
            ph.addPointFolder(f, parentId);
            addFoldersToHeirarchy(ph, f.getId(), folders);
        }
    }

    private int getMaxFolderId(final PointFolder root) {
        int maxId = 0;
        if(root == null)
            return maxId;
        if(root.getId() > maxId)
            maxId = root.getId();
        for(PointFolder subfolder : root.getSubfolders()) {
            int candidate = getMaxFolderId(subfolder);
            if(candidate > maxId)
                maxId = candidate;
        }
        return maxId;
    }


    public synchronized void savePointHierarchy(final PointFolder root) {
        // Assign ids to the folders.
        final List<Object> params = new ArrayList<>();
        final AtomicInteger folderId = new AtomicInteger(getMaxFolderId(root));
        for (PointFolder sf : root.getSubfolders())
            assignFolderIds(sf, 0, folderId, params);

        cachedPointHierarchy = new PointHierarchy(root);
        final ExtendedJdbcTemplate ejt2 = ejt;
        getTransactionTemplate().execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                // Dump the hierarchy table.
                ejt2.update("DELETE FROM dataPointHierarchy");

                // Reset the current point folders values in the points.
                ejt2.update("UPDATE dataPoints SET pointFolderId=0");

                // Save the point folders.
                if (folderId.get() > 0) {
                    StringBuilder sql = new StringBuilder();
                    sql.append("INSERT INTO dataPointHierarchy (id, parentId, name) VALUES ");
                    for (int i = 0; i < params.size()/3; i++) { //three fields per folder
                        if (i > 0)
                            sql.append(",");
                        sql.append("(?,?,?)");
                    }
                    ejt2.update(sql.toString(), params.toArray(new Object[params.size()]));
                }

                // Save the folder ids for the points.
                savePointsInFolder(root);
                PointHierarchyEventDispatcher.firePointHierarchySaved(root);
            }
        });
    }

    private void assignFolderIds(PointFolder folder, int parentId, AtomicInteger nextId, List<Object> params) {
        if(folder.getId() == Common.NEW_ID) {
            int id = nextId.incrementAndGet();
            folder.setId(id);
        }

        params.add(folder.getId());
        params.add(parentId);
        params.add(StringUtils.abbreviate(folder.getName(), 100));
        for (DataPointSummary point : folder.getPoints())
            point.setPointFolderId(folder.getId());
        for (PointFolder sf : folder.getSubfolders())
            assignFolderIds(sf, folder.getId(), nextId, params);
    }

    void savePointsInFolder(PointFolder folder) {
        // Save the points in the subfolders
        for (PointFolder sf : folder.getSubfolders())
            savePointsInFolder(sf);

        // Update the folder references in the points.
        if (!folder.getPoints().isEmpty()) {
            Object[] ids = new Object[folder.getPoints().size() + 1];
            ids[0] = folder.getId();
            StringBuilder sql = new StringBuilder();
            sql.append("UPDATE dataPoints SET pointFolderId=? WHERE id in (");
            boolean first = true;
            int pos = 1;
            for (DataPointSummary p : folder.getPoints()) {
                if (first)
                    first = false;
                else
                    sql.append(",");
                sql.append("?");
                ids[pos++] = p.getId();
            }
            sql.append(")");
            ejt.update(sql.toString(), ids);
        }
    }

    public void setFolderId(int dataPointId, int folderId) {
        ejt.update("UPDATE dataPoints SET pointFolderId=? WHERE id=?", folderId, dataPointId);
    }

    /**
     * Methods for AbstractDao below here
     */

    /*
     * (non-Javadoc)
     *
     * @see com.serotonin.m2m2.db.dao.AbstractDao#getTableName()
     */
    @Override
    protected String getTableName() {
        return SchemaDefinition.DATAPOINTS_TABLE;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.serotonin.m2m2.db.dao.AbstractDao#getXidPrefix()
     */
    @Override
    protected String getXidPrefix() {
        return DataPointVO.XID_PREFIX;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.serotonin.m2m2.db.dao.AbstractDao#voToObjectArray(com.serotonin.m2m2.vo.AbstractVO)
     */
    @Override
    protected Object[] voToObjectArray(DataPointVO vo) {
        return new Object[] { SerializationHelper.writeObject(vo), vo.getXid(), vo.getDataSourceId(), vo.getName(),
                vo.getDeviceName(), boolToChar(vo.isEnabled()), vo.getPointFolderId(), vo.getLoggingType(),
                vo.getIntervalLoggingPeriodType(), vo.getIntervalLoggingPeriod(), vo.getIntervalLoggingType(),
                vo.getTolerance(), boolToChar(vo.isPurgeOverride()), vo.getPurgeType(), vo.getPurgePeriod(),
                vo.getDefaultCacheSize(), boolToChar(vo.isDiscardExtremeValues()), vo.getEngineeringUnits(),
                vo.getReadPermission(), vo.getSetPermission(), vo.getTemplateId(), vo.getRollup(), vo.getPointLocator().getDataTypeId()};
    }

    /*
     * (non-Javadoc)
     *
     * @see com.serotonin.m2m2.db.dao.AbstractDao#getNewVo()
     */
    @Override
    public DataPointVO getNewVo() {
        return new DataPointVO();
    }



    /* (non-Javadoc)
     * @see com.serotonin.m2m2.db.dao.AbstractBasicDao#getPropertyTypeMap()
     */
    @Override
    protected LinkedHashMap<String, Integer> getPropertyTypeMap() {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
        map.put("id", Types.INTEGER);
        map.put("data", Types.BINARY); //Locator
        map.put("xid", Types.VARCHAR); //Xid
        map.put("dataSourceId",Types.INTEGER); //Dsid
        map.put("name", Types.VARCHAR); //Name
        map.put("deviceName", Types.VARCHAR); //Device Name
        map.put("enabled", Types.CHAR); //Enabled
        map.put("pointFolderId", Types.INTEGER); //Point Folder Id
        map.put("loggingType", Types.INTEGER); //Logging Type
        map.put("intervalLoggingPeriodType", Types.INTEGER); //Interval Logging Period Type
        map.put("intervalLoggingPeriod", Types.INTEGER); //Interval Logging Period
        map.put("intervalLoggingType", Types.INTEGER); //Interval Logging Type
        map.put("tolerance", Types.DOUBLE); //Tolerance
        map.put("purgeOverride", Types.CHAR); //Purge Override
        map.put("purgeType", Types.INTEGER); //Purge Type
        map.put("purgePeriod", Types.INTEGER); //Purge Period
        map.put("defaultCacheSize", Types.INTEGER); //Default Cache Size
        map.put("discardExtremeValues", Types.CHAR); //Discard Extremem Values
        map.put("engineeringUnits", Types.INTEGER); //get Engineering Units
        map.put("readPermission", Types.VARCHAR); // Read permission
        map.put("setPermission", Types.VARCHAR); // Set permission
        map.put("templateId", Types.INTEGER); //Template ID FK
        map.put("rollup", Types.INTEGER); //Common.Rollups type
        map.put("dataTypeId", Types.INTEGER); //Common.Rollups type

        return map;
    }

    /* (non-Javadoc)
     * @see com.serotonin.m2m2.db.dao.AbstractBasicDao#getJoins()
     */
    @Override
    protected List<JoinClause> getJoins() {
        List<JoinClause> joins = new ArrayList<JoinClause>();
        joins.add(new JoinClause(JOIN, SchemaDefinition.DATASOURCES_TABLE, "ds", "ds.id = dp.dataSourceId"));
        joins.add(new JoinClause(LEFT_JOIN, SchemaDefinition.TEMPLATES_TABLE, "template", "template.id = dp.templateId"));
        return joins;
    }

    /* (non-Javadoc)
     * @see com.serotonin.m2m2.db.dao.AbstractBasicDao#getIndexes()
     */
    @Override
    protected List<Index> getIndexes() {
        List<Index> indexes = new ArrayList<Index>();
        List<QueryAttribute> columns = new ArrayList<QueryAttribute>();
        //Data Source Name Force
        columns.add(new QueryAttribute("name", new HashSet<String>(), Types.VARCHAR));
        indexes.add(new Index("nameIndex", "ds", columns, "ASC"));

        //Data Source xid Force
        columns = new ArrayList<QueryAttribute>();
        columns.add(new QueryAttribute("xid", new HashSet<String>(), Types.VARCHAR));
        indexes.add(new Index("dataSourcesUn1", "ds", columns, "ASC"));

        //DeviceNameName Index Force
        columns = new ArrayList<QueryAttribute>();
        columns.add(new QueryAttribute("deviceName", new HashSet<String>(), Types.VARCHAR));
        columns.add(new QueryAttribute("name", new HashSet<String>(), Types.VARCHAR));
        indexes.add(new Index("deviceNameNameIndex", "dp", columns, "ASC"));

        //xid point name force
        columns = new ArrayList<QueryAttribute>();
        columns.add(new QueryAttribute("xid", new HashSet<String>(), Types.VARCHAR));
        columns.add(new QueryAttribute("name", new HashSet<String>(), Types.VARCHAR));
        indexes.add(new Index("xidNameIndex", "dp", columns, "ASC"));

        return indexes;
    }

    @Override
    protected Map<String, Comparator<DataPointVO>> getComparatorMap() {
        HashMap<String, Comparator<DataPointVO>> comparatorMap = new HashMap<>();

        comparatorMap.put("dataTypeString", new Comparator<DataPointVO>() {
            @Override
            public int compare(DataPointVO lhs, DataPointVO rhs) {
                return lhs.getDataTypeString().compareTo(rhs.getDataTypeString());
            }
        });

        comparatorMap.put("loggingTypeString", new Comparator<DataPointVO>() {
            @Override
            public int compare(DataPointVO lhs, DataPointVO rhs) {
                return lhs.getLoggingTypeString().compareTo(rhs.getLoggingTypeString());
            }
        });

        comparatorMap.put("loggingIntervalString", new Comparator<DataPointVO>() {
            @Override
            public int compare(DataPointVO lhs, DataPointVO rhs) {
                return lhs.getLoggingIntervalString().compareTo(rhs.getLoggingIntervalString());
            }
        });
        return comparatorMap;
    }

    @Override
    protected Map<String, IFilter<DataPointVO>> getFilterMap() {
        HashMap<String, IFilter<DataPointVO>> filterMap = new HashMap<>();

        filterMap.put("dataTypeString", new IFilter<DataPointVO>() {

            private String regex;

            @Override
            public boolean filter(DataPointVO vo) {
                return !vo.getDataTypeString().matches(regex);
            }

            @Override
            public void setFilter(Object matches) {
                this.regex = "(?i)" + (String) matches;

            }

        });

        filterMap.put("loggingTypeString", new IFilter<DataPointVO>() {
            private String regex;

            @Override
            public boolean filter(DataPointVO vo) {
                return !vo.getLoggingTypeString().matches(regex);
            }

            @Override
            public void setFilter(Object matches) {
                this.regex = "(?i)" + (String) matches;

            }
        });

        filterMap.put("loggingIntervalString", new IFilter<DataPointVO>() {
            private String regex;

            @Override
            public boolean filter(DataPointVO vo) {
                return !vo.getLoggingIntervalString().matches(regex);
            }

            @Override
            public void setFilter(Object matches) {
                this.regex = "(?i)" + (String) matches;

            }
        });
        return filterMap;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.serotonin.m2m2.db.dao.AbstractBasicDao#getPropertiesMap()
     */
    @Override
    protected Map<String, IntStringPair> getPropertiesMap() {
        HashMap<String, IntStringPair> map = new HashMap<String, IntStringPair>();
        map.put("dataSourceName", new IntStringPair(Types.VARCHAR, "ds.name"));
        map.put("dataSourceTypeName", new IntStringPair(Types.VARCHAR, "ds.dataSourceType"));
        map.put("dataSourceXid", new IntStringPair(Types.VARCHAR, "ds.xid"));
        map.put("dataSourceEditPermission", new IntStringPair(Types.VARCHAR, "ds.editPermission"));
        map.put("templateName", new IntStringPair(Types.VARCHAR, "template.name"));
        map.put("templateXid", new IntStringPair(Types.VARCHAR, "template.xid"));
        return map;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.serotonin.m2m2.db.dao.AbstractBasicDao#getRowMapper()
     */
    @Override
    public RowMapper<DataPointVO> getRowMapper() {
        return new DataPointMapper();
    }

    class DataPointMapper implements RowMapper<DataPointVO> {
        @Override
        public DataPointVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            int i = 0;
            int id = (rs.getInt(++i));

            //TODO Should catch Stream exceptions when a module is missing for an existing Datasource.
            DataPointVO dp = (DataPointVO) SerializationHelper.readObjectInContext(rs.getBinaryStream(++i));

            dp.setId(id);
            dp.setXid(rs.getString(++i));
            dp.setDataSourceId(rs.getInt(++i));
            dp.setName(rs.getString(++i));
            dp.setDeviceName(rs.getString(++i));
            dp.setEnabled(charToBool(rs.getString(++i)));
            dp.setPointFolderId(rs.getInt(++i));
            dp.setLoggingType(rs.getInt(++i));
            dp.setIntervalLoggingPeriodType(rs.getInt(++i));
            dp.setIntervalLoggingPeriod(rs.getInt(++i));
            dp.setIntervalLoggingType(rs.getInt(++i));
            dp.setTolerance(rs.getDouble(++i));
            dp.setPurgeOverride(charToBool(rs.getString(++i)));
            dp.setPurgeType(rs.getInt(++i));
            dp.setPurgePeriod(rs.getInt(++i));
            dp.setDefaultCacheSize(rs.getInt(++i));
            dp.setDiscardExtremeValues(charToBool(rs.getString(++i)));
            dp.setEngineeringUnits(rs.getInt(++i));
            dp.setReadPermission(rs.getString(++i));
            dp.setSetPermission(rs.getString(++i));
            //Because we read 0 for null
            dp.setTemplateId(rs.getInt(++i));
            if(rs.wasNull())
                dp.setTemplateId(null);
            dp.setRollup(rs.getInt(++i));

            // read and discard dataTypeId
            rs.getInt(++i);

            // Data source information from Extra Joins set in Constructor
            dp.setDataSourceName(rs.getString(++i));
            dp.setDataSourceXid(rs.getString(++i));
            dp.setDataSourceTypeName(rs.getString(++i));
            dp.setTemplateName(rs.getString(++i));
            dp.setTemplateXid(rs.getString(++i));

            dp.ensureUnitsCorrect();
            return dp;
        }
    }

    /**
     * Loads the event detectors, point comments and tags
     * @param vo
     */
    public void loadPartialRelationalData(DataPointVO vo) {
        this.setEventDetectors(vo);
        this.setPointComments(vo);
        vo.setTags(DataPointTagsDao.getInstance().getTagsForDataPointId(vo.getId()));
    }

    private void loadPartialRelationalData(List<DataPointVO> dps) {
        for (DataPointVO dp : dps) {
            loadPartialRelationalData(dp);
        }
    }

    /**
     * Loads the event detectors, point comments, tags data source and template name
     * Used by getFull()
     * @param vo
     */
    @Override
    public void loadRelationalData(DataPointVO vo) {
        this.loadPartialRelationalData(vo);
        this.loadDataSource(vo);
        this.setTemplateName(vo);
    }

    @Override
    public void saveRelationalData(DataPointVO vo, boolean insert) {
        saveEventDetectors(vo);

        Map<String, String> tags = vo.getTags();
        if (tags == null) {
            if (!insert) {
                // only delete the name and device tags, leave existing tags intact
                DataPointTagsDao.getInstance().deleteNameAndDeviceTagsForDataPointId(vo.getId());
            }
            tags = Collections.emptyMap();
        } else if (!insert) {
            // we only need to delete tags when doing an update
            DataPointTagsDao.getInstance().deleteTagsForDataPointId(vo.getId());
        }

        DataPointTagsDao.getInstance().insertTagsForDataPoint(vo, tags);
    }

    @Override
    public int copy(final int existingId, final String newXid, final String newName) {
        TransactionCallback<Integer> callback = new TransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                DataPointVO dataPoint = get(existingId);

                // Copy the vo
                DataPointVO copy = dataPoint.copy();
                copy.setId(Common.NEW_ID);
                copy.setXid(generateUniqueXid());
                copy.setName(dataPoint.getName());
                copy.setDeviceName(dataPoint.getDeviceName());
                copy.setDataSourceId(dataPoint.getDataSourceId());
                copy.setEnabled(dataPoint.isEnabled());
                copy.getComments().clear();

                // Copy the event detectors
                List<AbstractEventDetectorVO<?>> existing = EventDetectorDao.getInstance().getWithSourceId(
                        EventType.EventTypeNames.DATA_POINT, dataPoint.getId());
                List<AbstractPointEventDetectorVO<?>> detectors = new ArrayList<AbstractPointEventDetectorVO<?>>(existing.size());

                for (AbstractEventDetectorVO<?> ed : existing) {
                    AbstractPointEventDetectorVO<?> ped = (AbstractPointEventDetectorVO<?>)ed;
                    ped.setId(Common.NEW_ID);
                    ped.njbSetDataPoint(copy);
                }
                copy.setEventDetectors(detectors);

                Common.runtimeManager.saveDataPoint(copy);

                // Copy permissions.
                return copy.getId();
            }
        };

        return getTransactionTemplate().execute(callback);
    }

    /**
     * Load the datasource info into the DataPoint
     *
     * @param vo
     * @return
     */
    public void loadDataSource(DataPointVO vo) {

        //Get the values from the datasource table
        //TODO Could speed this up if necessary...
        DataSourceVO<?> dsVo = DataSourceDao.getInstance().get(vo.getDataSourceId());
        vo.setDataSourceName(dsVo.getName());
        vo.setDataSourceTypeName(dsVo.getDefinition().getDataSourceTypeName());
        vo.setDataSourceXid(dsVo.getXid());

    }

    /**
     * This method would be more accurately named loadTemplateName().
     * Loads the template from the database and sets templateName on the data point.
     *
     * @param vo
     */
    public void setTemplateName(DataPointVO vo){
        if(vo.getTemplateId() != null){
            BaseTemplateVO<?> template = TemplateDao.getInstance().get(vo.getTemplateId());
            if(template != null) {
                vo.setTemplateName(template.getName());
                vo.setTemplateXid(template.getXid());
            }
        }
    }

    @Override
    protected void insert(DataPointVO vo, String initiatorId) {
        super.insert(vo, initiatorId);
        this.countMonitor.increment();
    }

    @Override
    public void saveFull(DataPointVO vo) {
        //TODO Eventually Fix this up by using the new AbstractDao for the query
        this.saveDataPoint(vo); //This method throws a RuntimeException for licenses
    }

    /**
     *
     * Overridable method to extract the data
     *
     * @return
     */
    @Override
    public ResultSetExtractor<List<DataPointVO>> getResultSetExtractor(final RowMapper<DataPointVO> rowMapper,
            final FilterListCallback<DataPointVO> filters) {

        return new ResultSetExtractor<List<DataPointVO>>() {
            List<DataPointVO> results = new ArrayList<>();
            int rowNum = 0;

            @Override
            public List<DataPointVO> extractData(ResultSet rs) throws SQLException, DataAccessException {
                while (rs.next()) {
                    try {
                        DataPointVO row = rowMapper.mapRow(rs, rowNum);
                        //Should we filter the row?
                        if (!filters.filterRow(row, rowNum++))
                            results.add(row);
                    }
                    catch (ShouldNeverHappenException e) {
                        // If the module was removed but there are still records in the database, this exception will be
                        // thrown. Check the inner exception to confirm.
                        if (e.getCause() instanceof ModuleNotLoadedException) {
                            // Yep. Log the occurrence and continue.
                            LOG.error("Data point with xid '" + rs.getString("xid")
                            + "' could not be loaded. Is its module missing?", e.getCause());
                        }
                        else {
                            LOG.error(e.getMessage(), e);
                        }
                    }
                }
                return results;
            }
        };
    }

    /**
     * Get a list of all Data Points using a given template
     * @param id
     * @return
     */
    public List<DataPointVO> getByTemplate(int id) {
        return getByTemplate(id, true);
    }

    /**
     * Get a list of all Data Points using a given template
     * @param id
     * @return
     */
    public List<DataPointVO> getByTemplate(int id, boolean setRelational) {
        List<DataPointVO> dps = query(DATA_POINT_SELECT + " WHERE templateId=?", new Object[]{id}, new DataPointRowMapper());
        if (dps != null && setRelational)
            loadPartialRelationalData(dps); //We will need this to restart the points after updating them
        return dps;
    }

    /**
     * @param node
     * @param permissions
     * @param updateSetPermissions - true will update set, false will update read
     * @return
     */
    public long bulkUpdatePermissions(ASTNode root, String uncleanPermissions,
            boolean updateSetPermissions) {

        //Anything to do?
        if(StringUtils.isEmpty(uncleanPermissions))
            return 0;

        //Trim off any ending commas
        if(uncleanPermissions.endsWith(",")){
            uncleanPermissions = uncleanPermissions.substring(0, uncleanPermissions.length()-1);
        }

        final String newPermissions = uncleanPermissions;
        long count;

        if(root == null){
            //All points
            count = ejt.execute(new DataPointPermissionChangePreparedStatementCreator("SELECT dp.id,dp.readPermission,dp.setPermission FROM dataPoints AS dp FOR UPDATE; ", new ArrayList<Object>()),
                    new DataPointPermissionChangeCallback(updateSetPermissions, newPermissions));
        }else{
            switch(Common.databaseProxy.getType()){
                case H2:
                case DERBY:
                case MSSQL:
                case MYSQL:
                case POSTGRES:
                default:
                    final SQLStatement select = new SQLStatement("SELECT dp.id,dp.readPermission,dp.setPermission FROM ", COUNT_BASE, this.joins, this.tableName, this.TABLE_PREFIX, true, false, super.getIndexes(), this.databaseType);
                    root.accept(new RQLToSQLSelect<DataPointVO>(this), select);
                    select.build();
                    count = ejt.execute(new DataPointPermissionChangePreparedStatementCreator(select.getSelectSql() + " FOR UPDATE; ", select.getSelectArgs()),
                            new DataPointPermissionChangeCallback(updateSetPermissions, newPermissions));
                    break;
            }
        }

        return count;
    }

    /**
     * @param node
     * @param updateSetPermissions - true will update set, false will update read
     * @return
     *
     */
    public long bulkClearPermissions(ASTNode root, boolean updateSetPermissions) {

        long count;
        if(root == null){
            //All points
            count = ejt.execute(new DataPointPermissionChangePreparedStatementCreator("SELECT dp.id,dp.readPermission,dp.setPermission FROM dataPoints AS dp FOR UPDATE; ", new ArrayList<Object>()),
                    new DataPointPermissionChangeCallback(updateSetPermissions, null));
        }else{
            switch(Common.databaseProxy.getType()){
                case H2:
                case DERBY:
                case MSSQL:
                case MYSQL:
                case POSTGRES:
                default:
                    final SQLStatement select = new SQLStatement("SELECT dp.id,dp.readPermission,dp.setPermission FROM ", COUNT_BASE, this.joins, this.tableName, this.TABLE_PREFIX, true, false, super.getIndexes(), this.databaseType);
                    root.accept(new RQLToSQLSelect<DataPointVO>(this), select);
                    select.build();
                    count = ejt.execute(new DataPointPermissionChangePreparedStatementCreator(select.getSelectSql() + " FOR UPDATE; ", select.getSelectArgs()),
                            new DataPointPermissionChangeCallback(updateSetPermissions, null));
                    break;
            }
        }
        return count;
    }

    /**
     * Helper to create a data point permission query where the results can be modified
     * @author Terry Packer
     *
     */
    class DataPointPermissionChangePreparedStatementCreator implements PreparedStatementCreator{

        final String select;
        final Object[] args;

        public DataPointPermissionChangePreparedStatementCreator(String sql, List<Object> args){
            this.select = sql;
            this.args = args.toArray();
        }

        @Override
        public PreparedStatement createPreparedStatement(Connection con) throws SQLException {
            PreparedStatement stmt = con.prepareStatement(select, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
            //Set the values
            ArgumentPreparedStatementSetter setter = new ArgumentPreparedStatementSetter(args);
            setter.setValues(stmt);
            return stmt;
        }
    }

    /**
     * Callback handler for modifying a permission via a data point query
     * @author Terry Packer
     *
     */
    class DataPointPermissionChangeCallback implements PreparedStatementCallback<Integer>{
        final String ID = "id";
        final String SET_PERMISSION = "setPermission";
        final String READ_PERMISSION = "readPermission";

        //count of modified data points
        int count;
        //Comma separated list of permissions or one permission
        final Set<String> newPermissions;
        //Name of permission to change (setPermission or readPermission)
        final String permissionName;

        public DataPointPermissionChangeCallback(boolean updateSetPermissions, String newPermissions){
            this.count = 0;
            this.permissionName = updateSetPermissions ? SET_PERMISSION : READ_PERMISSION;
            this.newPermissions = new HashSet<>(Permissions.explodePermissionGroups(newPermissions));
        }

        /* (non-Javadoc)
         * @see org.springframework.jdbc.core.PreparedStatementCallback#doInPreparedStatement(java.sql.PreparedStatement)
         */
        @Override
        public Integer doInPreparedStatement(PreparedStatement stmt) throws SQLException, DataAccessException {
            try{
                stmt.execute();
                ResultSet rs = stmt.getResultSet();
                boolean updated;
                while(rs.next()){
                    updated = false;
                    String existingPermissions = rs.getString(permissionName);
                    if(StringUtils.isEmpty(existingPermissions)){
                        //No permissions, only update if new permissions are not empty
                        if(!newPermissions.isEmpty()){
                            existingPermissions = Permissions.implodePermissionGroups(newPermissions);
                            updated = true;
                        }
                    }else{

                        if(!newPermissions.isEmpty()){
                            //Don't duplicate permissions
                            Set<String> existing = new HashSet<>(Permissions.explodePermissionGroups(existingPermissions));
                            int originalSize = existing.size();
                            newPermissions.addAll(existing);
                            if(newPermissions.size() != originalSize) {
                                existingPermissions = Permissions.implodePermissionGroups(newPermissions);
                                updated = true;
                            }
                        }else{
                            //Set to null
                            existingPermissions = null;
                            updated = true;
                        }
                    }
                    if(updated){
                        count++;
                        rs.updateString(permissionName, existingPermissions);
                        rs.updateRow();
                        DataPointRT rt = Common.runtimeManager.getDataPoint(rs.getInt(ID));
                        if(rt != null)
                            rt.getVO().setSetPermission(existingPermissions);
                    }
                }

                //clear the cache to pickup our changes
                if(count > 0)
                    clearPointHierarchyCache();

                return count;
            }finally{
                if(stmt != null) { stmt.close(); }
            }
        }
    }

    /**
     * Gets all data points that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     * For a superadmin user it will get all data points in the system.
     *
     * @param user
     * @return
     */
    public List<DataPointVO> dataPointsForUser(User user) {
        List<DataPointVO> result = new ArrayList<>();
        dataPointsForUser(user, (item, index) -> result.add(item));
        return result;
    }

    /**
     * Gets all data points that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     * For a superadmin user it will get all data points in the system.
     *
     * @param user
     * @param callback
     */
    public void dataPointsForUser(User user, MappedRowCallback<DataPointVO> callback) {
        dataPointsForUser(user, callback, null, null, null);
    }

    /**
     * Gets all data points that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     * For a superadmin user it will get all data points in the system.
     *
     * @param user
     * @param callback
     * @param sort (may be null)
     * @param limit (may be null)
     * @param offset (may be null)
     */
    public void dataPointsForUser(User user, MappedRowCallback<DataPointVO> callback, List<SortField<Object>> sort, Integer limit, Integer offset) {
        Condition condition = null;
        if (!user.hasAdminPermission()) {
            condition = this.userHasPermission(user);
        }
        SelectJoinStep<Record> select = this.create.select(this.fields).from(this.joinedTable);
        this.customizedQuery(select, condition, sort, limit, offset, callback);
    }

    /**
     * Gets data points for a set of tags that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     *
     * @param restrictions
     * @param user
     * @return
     */
    public List<DataPointVO> dataPointsForTags(Map<String, String> restrictions, User user) {
        List<DataPointVO> result = new ArrayList<>();
        dataPointsForTags(restrictions, user, (item, index) -> result.add(item));
        return result;
    }

    /**
     * Gets data points for a set of tags that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     *
     * @param restrictions
     * @param user
     * @param callback
     */
    public void dataPointsForTags(Map<String, String> restrictions, User user, MappedRowCallback<DataPointVO> callback) {
        dataPointsForTags(restrictions, user, callback, null, null, null);
    }

    /**
     * Gets data points for a set of tags that a user has access to (i.e. readPermission, setPermission or the datasource editPermission).
     *
     * @param restrictions
     * @param user
     * @param callback
     * @param sort (may be null)
     * @param limit (may be null)
     * @param offset (may be null)
     */
    public void dataPointsForTags(Map<String, String> restrictions, User user, MappedRowCallback<DataPointVO> callback, List<SortField<Object>> sort, Integer limit, Integer offset) {
        if (restrictions.isEmpty()) {
            throw new IllegalArgumentException("restrictions should not be empty");
        }

        Map<String, Name> tagKeyToColumn = DataPointTagsDao.getInstance().tagKeyToColumn(restrictions.keySet());

        List<Condition> conditions = restrictions.entrySet().stream().map(e -> {
            return DSL.field(DATA_POINT_TAGS_PIVOT_ALIAS.append(tagKeyToColumn.get(e.getKey()))).eq(e.getValue());
        }).collect(Collectors.toCollection(ArrayList::new));

        if (!user.hasAdminPermission()) {
            conditions.add(this.userHasPermission(user));
        }

        Table<Record> pivotTable = DataPointTagsDao.getInstance().createTagPivotSql(tagKeyToColumn).asTable().as(DATA_POINT_TAGS_PIVOT_ALIAS);

        SelectOnConditionStep<Record> select = this.create.select(this.fields).from(this.joinedTable).leftJoin(pivotTable)
                .on(DataPointTagsDao.PIVOT_ALIAS_DATA_POINT_ID.eq(ID));

        this.customizedQuery(select, DSL.and(conditions), sort, limit, offset, callback);
    }

    @Override
    protected RQLToCondition createRqlToCondition() {
        // we create one every time as they are stateful for this DAO
        return null;
    }

    @Override
    public <R extends Record> SelectJoinStep<R> joinTables(SelectJoinStep<R> select, ConditionSortLimit conditions) {
        if (conditions instanceof ConditionSortLimitWithTagKeys) {
            Map<String, Name> tagKeyToColumn = ((ConditionSortLimitWithTagKeys) conditions).getTagKeyToColumn();
            if (!tagKeyToColumn.isEmpty()) {
                Table<Record> pivotTable = DataPointTagsDao.getInstance().createTagPivotSql(tagKeyToColumn).asTable().as(DATA_POINT_TAGS_PIVOT_ALIAS);

                return select.leftJoin(pivotTable)
                        .on(DataPointTagsDao.PIVOT_ALIAS_DATA_POINT_ID.eq(ID));
            }
        }
        return select;
    }

    @Override
    public ConditionSortLimitWithTagKeys rqlToCondition(ASTNode rql) {
        // RQLToConditionWithTagKeys is stateful, we need to create a new one every time
        RQLToConditionWithTagKeys rqlToSelect = new RQLToConditionWithTagKeys(this.propertyToField, this.valueConverterMap);
        return rqlToSelect.visit(rql);
    }

    public static final String PERMISSION_START_REGEX = "(^|[,])\\s*";
    public static final String PERMISSION_END_REGEX = "\\s*($|[,])";

    public Condition userHasPermission(User user) {
        Set<String> userPermissions = user.getPermissionsSet();
        List<Condition> conditions = new ArrayList<>(userPermissions.size() * 3);

        for (String userPermission : userPermissions) {
            conditions.add(fieldMatchesUserPermission(READ_PERMISSION, userPermission));
            conditions.add(fieldMatchesUserPermission(SET_PERMISSION, userPermission));
            conditions.add(fieldMatchesUserPermission(DataSourceDao.EDIT_PERMISSION, userPermission));
        }

        return DSL.or(conditions);
    }

    public Condition userHasSetPermission(User user) {
        Set<String> userPermissions = user.getPermissionsSet();
        List<Condition> conditions = new ArrayList<>(userPermissions.size() * 2);

        for (String userPermission : userPermissions) {
            conditions.add(fieldMatchesUserPermission(SET_PERMISSION, userPermission));
            conditions.add(fieldMatchesUserPermission(DataSourceDao.EDIT_PERMISSION, userPermission));
        }

        return DSL.or(conditions);
    }

    public Condition userHasEditPermission(User user) {
        Set<String> userPermissions = user.getPermissionsSet();
        List<Condition> conditions = new ArrayList<>(userPermissions.size());

        for (String userPermission : userPermissions) {
            conditions.add(fieldMatchesUserPermission(DataSourceDao.EDIT_PERMISSION, userPermission));
        }

        return DSL.or(conditions);
    }

    Condition fieldMatchesUserPermission(Field<String> field, String userPermission) {
        return DSL.or(
                field.eq(userPermission),
                DSL.and(
                        field.isNotNull(),
                        field.notEqual(""),
                        field.likeRegex(PERMISSION_START_REGEX + userPermission + PERMISSION_END_REGEX)
                        )
                );
    }

    protected void notifyTagsUpdated(DataPointVO dataPoint) {
        this.eventPublisher.publishEvent(new DataPointTagsUpdatedEvent(this, dataPoint));
    }
}
