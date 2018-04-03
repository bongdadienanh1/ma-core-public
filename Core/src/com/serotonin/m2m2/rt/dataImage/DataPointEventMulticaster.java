/*
    Copyright (C) 2014 Infinite Automation Systems Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.serotonin.m2m2.rt.dataImage;

import java.util.Map;

public class DataPointEventMulticaster implements DataPointListener {
    protected final DataPointListener a, b;

    protected DataPointEventMulticaster(DataPointListener a, DataPointListener b) {
        this.a = a;
        this.b = b;
    }

    protected DataPointListener remove(DataPointListener oldl) {
        if (oldl == a)
            return b;
        if (oldl == b)
            return a;
        DataPointListener a2 = remove(a, oldl);
        DataPointListener b2 = remove(b, oldl);
        if (a2 == a && b2 == b) {
            return this;
        }
        return add(a2, b2);
    }

    public static DataPointListener add(DataPointListener a, DataPointListener b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        return new DataPointEventMulticaster(a, b);
    }

    public static DataPointListener remove(DataPointListener l, DataPointListener oldl) {
        if (l == oldl || l == null)
            return null;

        if (l instanceof DataPointEventMulticaster)
            return ((DataPointEventMulticaster) l).remove(oldl);

        return l;
    }

    private static int getListenerCount(DataPointListener l) {
        if (l instanceof DataPointEventMulticaster) {
            DataPointEventMulticaster mc = (DataPointEventMulticaster) l;
            return getListenerCount(mc.a) + getListenerCount(mc.b);
        }

        return 1;
    }

    private static int populateListenerArray(DataPointListener[] a, DataPointListener l, int index) {
        if (l instanceof DataPointEventMulticaster) {
            DataPointEventMulticaster mc = (DataPointEventMulticaster) l;
            int lhs = populateListenerArray(a, mc.a, index);
            return populateListenerArray(a, mc.b, lhs);
        }

        if (a.getClass().getComponentType().isInstance(l)) {
            a[index] = l;
            return index + 1;
        }

        return index;
    }

    public static DataPointListener[] getListeners(DataPointListener l) {
        int n = getListenerCount(l);
        DataPointListener[] result = new DataPointListener[n];
        populateListenerArray(result, l, 0);
        return result;
    }

    //
    // /
    // / DataPointListener interface
    // /
    // /
	@Override
    public void pointChanged(PointValueTime oldValue, PointValueTime newValue) {
	    try {
	        a.pointChanged(oldValue, newValue);
	    } finally {
	        b.pointChanged(oldValue, newValue);
	    }
    }
	@Override
    public void pointSet(PointValueTime oldValue, PointValueTime newValue) {
	    try {
	        a.pointSet(oldValue, newValue);
	    } finally {
	        b.pointSet(oldValue, newValue);
	    }
    }
	@Override
    public void pointUpdated(PointValueTime newValue) {
	    try {
	        a.pointUpdated(newValue);
	    } finally {
	        b.pointUpdated(newValue);
	    }
    }
	@Override
    public void pointBackdated(PointValueTime value) {
	    try {
	        a.pointBackdated(value);
	    } finally {
	        b.pointBackdated(value);
	    }
    }
	@Override
    public void pointInitialized() {
	    try {
	        a.pointInitialized();
	    } finally {
	        b.pointInitialized();
	    }
    }
	@Override
    public void pointTerminated() {
	    try {
	        a.pointTerminated();
	    } finally {
	        b.pointTerminated();
	    }
    }

	@Override
	public void pointLogged(PointValueTime value) {
	    try {
	        a.pointLogged(value);
	    } finally {
	        b.pointLogged(value);
	    }
	}
	
	/* (non-Javadoc)
	 * @see com.serotonin.m2m2.rt.dataImage.DataPointListener#attributeChanged(java.util.Map)
	 */
	@Override
	public void attributeChanged(Map<String, Object> attributes) {
	    try {
	        a.attributeChanged(attributes);
	    } finally {
	        b.attributeChanged(attributes);
	    }
	}

	/* (non-Javadoc)
	 * @see com.serotonin.m2m2.rt.dataImage.DataPointListener#getListenerName()
	 */
	@Override
	public String getListenerName() {
		String path = "";
		if(a != null)
			path += a.getListenerName();
		if(b != null)
			path += "," + b.getListenerName();
		return path;
	}
}
