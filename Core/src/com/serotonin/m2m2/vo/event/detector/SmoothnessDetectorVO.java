/**
 * Copyright (C) 2016 Infinite Automation Software. All rights reserved.
 * @author Terry Packer
 */
package com.serotonin.m2m2.vo.event.detector;

import java.io.IOException;

import com.serotonin.json.JsonException;
import com.serotonin.json.JsonReader;
import com.serotonin.json.ObjectWriter;
import com.serotonin.json.spi.JsonProperty;
import com.serotonin.json.type.JsonObject;
import com.serotonin.m2m2.DataTypes;
import com.serotonin.m2m2.db.dao.DataPointDao;
import com.serotonin.m2m2.i18n.ProcessResult;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.rt.event.detectors.AbstractEventDetectorRT;
import com.serotonin.m2m2.rt.event.detectors.SmoothnessDetectorRT;
import com.serotonin.m2m2.view.text.TextRenderer;

/**
 * @author Terry Packer
 *
 */
public class SmoothnessDetectorVO extends TimeoutDetectorVO<SmoothnessDetectorVO>{

	private static final long serialVersionUID = 1L;
	
	@JsonProperty
	private double limit;
	@JsonProperty
	private double boxcar = 3;
	
	public SmoothnessDetectorVO() {
		super(new int[] { DataTypes.NUMERIC });
	}

	public double getLimit() {
		return limit;
	}

	public void setLimit(double limit) {
		this.limit = limit;
	}

	public double getBoxcar() {
		return boxcar;
	}

	public void setBoxcar(double boxcar) {
		this.boxcar = boxcar;
	}
	
	/* (non-Javadoc)
	 * @see com.serotonin.m2m2.vo.event.detector.TimeoutDetectorVO#validate(com.serotonin.m2m2.i18n.ProcessResult)
	 */
	@Override
	public void validate(ProcessResult response) {
		super.validate(response);
		
		if(Double.isInfinite(limit) || Double.isNaN(limit))
			response.addContextualMessage("limit", "validate.invalidValue");
		if(boxcar <= 2)
			response.addContextualMessage("boxcar", "validate.greaterThan", 2);
	}

	/* (non-Javadoc)
	 * @see com.serotonin.m2m2.vo.event.detector.AbstractEventDetectorVO#createRuntime()
	 */
	@Override
	public AbstractEventDetectorRT<SmoothnessDetectorVO> createRuntime() {
		return new SmoothnessDetectorRT(this);
	}

	/* (non-Javadoc)
	 * @see com.serotonin.m2m2.vo.event.detector.AbstractEventDetectorVO#getConfigurationDescription()
	 */
	@Override
	protected TranslatableMessage getConfigurationDescription() {
	    if(dataPoint == null)
            dataPoint = DataPointDao.getInstance().getDataPoint(sourceId);
		TranslatableMessage durationDesc = getDurationDescription();

        if (durationDesc == null)
            return new TranslatableMessage("event.detectorVo.smoothness", dataPoint.getTextRenderer().getText(
                    limit, TextRenderer.HINT_SPECIFIC));
        return new TranslatableMessage("event.detectorVo.smoothnessPeriod", dataPoint.getTextRenderer()
                    .getText(limit, TextRenderer.HINT_SPECIFIC), durationDesc);
	}

    @Override
    public void jsonWrite(ObjectWriter writer) throws IOException, JsonException {
    	super.jsonWrite(writer);
    }

    @Override
    public void jsonRead(JsonReader reader, JsonObject jsonObject) throws JsonException {
    	super.jsonRead(reader, jsonObject);
    }
}
