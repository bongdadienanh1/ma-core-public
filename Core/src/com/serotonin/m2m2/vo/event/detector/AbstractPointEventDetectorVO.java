/**
 * Copyright (C) 2016 Infinite Automation Software. All rights reserved.
 * @author Terry Packer
 */
package com.serotonin.m2m2.vo.event.detector;

import java.io.IOException;
import java.util.Arrays;

import org.apache.commons.lang3.ArrayUtils;

import com.serotonin.json.JsonException;
import com.serotonin.json.JsonReader;
import com.serotonin.json.ObjectWriter;
import com.serotonin.json.type.JsonObject;
import com.serotonin.m2m2.db.dao.DataPointDao;
import com.serotonin.m2m2.i18n.ProcessResult;
import com.serotonin.m2m2.i18n.TranslatableJsonException;
import com.serotonin.m2m2.rt.event.AlarmLevels;
import com.serotonin.m2m2.rt.event.type.DataPointEventType;
import com.serotonin.m2m2.vo.DataPointVO;
import com.serotonin.m2m2.vo.event.EventTypeVO;

/**
 * @author Terry Packer
 *
 */
public abstract class AbstractPointEventDetectorVO<T extends AbstractPointEventDetectorVO<T>> extends AbstractEventDetectorVO<T> {

    private static final long serialVersionUID = 1L;

    public static final String XID_PREFIX = "PED_";
    protected static final String MISSING_PROP_TRANSLATION_KEY = "emport.error.ped.missingAttr";

    private AlarmLevels alarmLevel = AlarmLevels.NONE;

    //Extra Fields
    protected DataPointVO dataPoint;
    private final int[] supportedDataTypes;

    public AbstractPointEventDetectorVO(int[] supportedDataTypes){
        this.supportedDataTypes = supportedDataTypes;
    }

    public AlarmLevels getAlarmLevel() {
        return alarmLevel;
    }
    public void setAlarmLevel(AlarmLevels alarmLevel) {
        this.alarmLevel = alarmLevel;
    }

    public DataPointVO njbGetDataPoint() {
        return dataPoint;
    }

    public void njbSetDataPoint(DataPointVO dataPoint) {
        this.dataPoint = dataPoint;
    }

    /**
     * What data types are supported
     * @param dataType
     * @return
     */
    public boolean supports(int dataType) {
        return ArrayUtils.contains(supportedDataTypes, dataType);
    }

    public int[] getSupportedDataTypes(){
        return supportedDataTypes;
    }
    /* (non-Javadoc)
     * @see com.serotonin.m2m2.vo.event.detector.AbstractEventDetectorVO#isRtnApplicable()
     */
    @Override
    public boolean isRtnApplicable() {
        return true;
    }

    @Override
    public EventTypeVO getEventType() {
        if(this.dataPoint == null)
            this.dataPoint = DataPointDao.getInstance().get(sourceId);
        return new EventTypeVO(new DataPointEventType(sourceId, id), getDescription(),
                alarmLevel);
    }

    /* (non-Javadoc)
     * @see com.serotonin.m2m2.vo.AbstractVO#validate(com.serotonin.m2m2.i18n.ProcessResult)
     */
    @Override
    public void validate(ProcessResult response) {
        super.validate(response);

        if(this.dataPoint == null)
            this.dataPoint = DataPointDao.getInstance().get(sourceId);

        //We currently don't check to see if the point exists
        // because of SQL constraints

        //Is valid data type
        boolean valid = false;
        for(int type : this.supportedDataTypes){
            if(type == this.dataPoint.getPointLocator().getDataTypeId()){
                valid = true;
                break;
            }
        }
        if(!valid){
            //Add message
            response.addContextualMessage("dataPoint.pointLocator.dataType", "validate.invalidValue");
        }

        //Is valid alarm level
        if (alarmLevel == null)
            response.addContextualMessage("alarmLevel", "validate.invalidValue");

    }

    @Override
    public void jsonWrite(ObjectWriter writer) throws IOException, JsonException {
        super.jsonWrite(writer);
        writer.writeEntry("alarmLevel", alarmLevel.name());
    }

    @Override
    public void jsonRead(JsonReader reader, JsonObject jsonObject) throws JsonException {
        //TODO Some configuration descriptions will throw NPE if dataPoint isn't set
        //this.dataPoint = DataPointDao.getInstance().get(sourceId);

        super.jsonRead(reader, jsonObject);

        String text = jsonObject.getString("alarmLevel");
        if (text != null) {
            try {
                alarmLevel = AlarmLevels.fromName(text);
            } catch (IllegalArgumentException e) {
                throw new TranslatableJsonException("emport.error.ped.invalid", "alarmLevel", text,
                        Arrays.asList(AlarmLevels.values()));
            }
        }
    }
}
