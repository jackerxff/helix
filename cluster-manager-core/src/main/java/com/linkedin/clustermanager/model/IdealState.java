package com.linkedin.clustermanager.model;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


public class IdealState
{

    private final ZNRecord _record;

    public IdealState()
    {
        _record = new ZNRecord();
    }

    public IdealState(ZNRecord record)
    {
        this._record = record;

    }

    public void set(String key, String instanceName, String state)
    {
        Map<String, String> mapField = _record.getMapField(key);
        if (mapField == null)
        {
            _record.setMapField(key, new TreeMap<String, String>());
        }
        _record.getMapField(key).put(instanceName, state);
    }

    public String get(String key, String instanceName)
    {

        Map<String, String> mapField = _record.getMapField(key);
        if (mapField != null)
        {
            return mapField.get(instanceName);
        }
        return null;
    }

    public Set<String> stateUnitSet()
    {
        return _record.getMapFields().keySet();
    }

    public Map<String, String> getInstanceStateMap(String stateUnitKey)
    {
        return _record.getMapField(stateUnitKey);
    }
}