package com.linkedin.clustermanager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.testng.annotations.Test;
import org.testng.AssertJUnit;

import com.linkedin.clustermanager.tools.IdealStateCalculatorForStorageNode;

public class TestEspressoStorageClusterIdealState
{
//public static void main(String[] args)
  @Test
  public void testInvocation() throws Exception
  {
    List<String> instanceNames = new ArrayList<String>();
    for(int i = 0;i < 20; i++)
    {
      instanceNames.add("localhost:123" + i);
    }
    int partitions = 4096, replicas = 3;
    Map<String, Object> resultOriginal = IdealStateCalculatorForStorageNode.calculateInitialIdealState(instanceNames,1, partitions, replicas);
    
    Verify(resultOriginal, partitions,replicas);
    printStat(resultOriginal);
    
    Map<String, Object> result1 = IdealStateCalculatorForStorageNode.calculateInitialIdealState(instanceNames,1, partitions, replicas);
    
    List<String> instanceNames2 = new ArrayList<String>();
    for(int i = 30;i < 35; i++)
    {
      instanceNames2.add("localhost:123" + i);
    }
    
    IdealStateCalculatorForStorageNode.calculateNextIdealState(instanceNames2,1, result1);
    
    List<String> instanceNames3 = new ArrayList<String>();
    for(int i = 35;i < 40; i++)
    {
      instanceNames3.add("localhost:123" + i);
    }
    
    IdealStateCalculatorForStorageNode.calculateNextIdealState(instanceNames3,1, result1);
    Verify(result1, partitions,replicas);
    compareResult(resultOriginal, result1);
    
  }
  
  public void Verify(Map<String, Object> result, int partitions, int replicas)
  {
    Map<String, List<Integer>> masterAssignmentMap = (Map<String, List<Integer>>) (result.get("MasterAssignmentMap"));
    Map<String, Map<String, List<Integer>>> nodeSlaveAssignmentMap = (Map<String, Map<String, List<Integer>>>)(result.get("SlaveAssignmentMap"));
    
    int instanceNum = masterAssignmentMap.size();
    AssertJUnit.assertTrue( partitions == (Integer)(result.get("partitions")));
    
    // Verify master partitions covers all master partitions on each node
    Map<Integer, Integer> masterCounterMap = new TreeMap<Integer, Integer>();
    for(int i = 0;i<partitions; i++)
    {
      masterCounterMap.put(i, 0);
    }
    
    int minMasters = Integer.MAX_VALUE, maxMasters = Integer.MIN_VALUE;
    for(String instanceName : masterAssignmentMap.keySet())
    {
      List<Integer> masterList = masterAssignmentMap.get(instanceName);
      // the assert needs to be changed when weighting is introduced
      // AssertJUnit.assertTrue(masterList.size() == partitions /masterAssignmentMap.size() | masterList.size() == (partitions /masterAssignmentMap.size()+1) );
      
      for(Integer x : masterList)
      {
        AssertJUnit.assertTrue(masterCounterMap.get(x) == 0);
        masterCounterMap.put(x,1);
      }
      if(minMasters > masterList.size())
      {
        minMasters = masterList.size();
      }
      if(maxMasters < masterList.size())
      {
        maxMasters = masterList.size();
      }
    }
    //AssertJUnit.assertTrue((maxMasters - minMasters) <= 1);
    System.out.println("Masters: max: "+maxMasters+" Min:"+ minMasters);
    // Each master partition should occur only once
    for(int i = 0;i < partitions; i++)
    {
      AssertJUnit.assertTrue(masterCounterMap.get(i) == 1);
    }
    AssertJUnit.assertTrue(masterCounterMap.size() == partitions);
    
    // for each node, verify the master partitions and the slave partition assignment map
    AssertJUnit.assertTrue(masterAssignmentMap.size() == nodeSlaveAssignmentMap.size());
    for(String instanceName: masterAssignmentMap.keySet())
    {
      AssertJUnit.assertTrue(nodeSlaveAssignmentMap.containsKey(instanceName));
      
      Map<String, List<Integer>> slaveAssignmentMap = nodeSlaveAssignmentMap.get(instanceName);
      Map<Integer, Integer> slaveCountMap = new TreeMap<Integer, Integer>();
      List<Integer> masterList = masterAssignmentMap.get(instanceName);
      
      for(Integer masterPartitionId : masterList)
      {
        slaveCountMap.put(masterPartitionId, 0);
      }
      // Make sure that masterList are covered replica times by the slave assignment.
      int minSlaves = Integer.MAX_VALUE, maxSlaves = Integer.MIN_VALUE;
      for(String hostInstance : slaveAssignmentMap.keySet())
      {
        List<Integer> slaveAssignment = slaveAssignmentMap.get(hostInstance);
        Set<Integer> occurenceSet = new HashSet<Integer>();
        // Each slave should occur only once in the list, since the list is per-node slaves
        for(Integer slavePartition : slaveAssignment)
        {
          AssertJUnit.assertTrue(!occurenceSet.contains(slavePartition));
          occurenceSet.add(slavePartition);
          
          slaveCountMap.put(slavePartition, slaveCountMap.get(slavePartition) + 1);
        }
        if(minSlaves > slaveAssignment.size())
        {
          minSlaves = slaveAssignment.size();
        }
        if(maxSlaves < slaveAssignment.size())
        {
          maxSlaves = slaveAssignment.size();
        }
      }
      AssertJUnit.assertTrue(maxSlaves - minSlaves <= 1);
      // for each node, the slave assignment map should cover the masters for exactly replica 
      // times
      AssertJUnit.assertTrue(slaveCountMap.size() == masterList.size());
      System.out.println("Slaves: max: "+maxSlaves+" Min:"+ minSlaves);
      for(Integer masterPartitionId : masterList)
      {
        AssertJUnit.assertTrue(slaveCountMap.get(masterPartitionId) == replicas);
      }
    }
    
  }
  
  public void printStat(Map<String, Object> result)
  {
    // print out master distribution
    
    // print out slave distribution
    
  }
  
  public void compareResult(Map<String, Object> result1, Map<String, Object> result2)
  {
    Map<String, List<Integer>> masterAssignmentMap1 = (Map<String, List<Integer>>) (result1.get("MasterAssignmentMap"));
    Map<String, Map<String, List<Integer>>> nodeSlaveAssignmentMap1 = (Map<String, Map<String, List<Integer>>>)(result1.get("SlaveAssignmentMap"));
    
    Map<String, List<Integer>> masterAssignmentMap2 = (Map<String, List<Integer>>) (result2.get("MasterAssignmentMap"));
    Map<String, Map<String, List<Integer>>> nodeSlaveAssignmentMap2 = (Map<String, Map<String, List<Integer>>>)(result2.get("SlaveAssignmentMap"));
    
    int commonMasters = 0;
    int commonSlaves = 0;
    int partitions = (Integer)(result1.get("partitions"));
    int replicas = (Integer)(result1.get("replicas"));
    
    AssertJUnit.assertTrue((Integer)(result2.get("partitions")) == partitions);
    AssertJUnit.assertTrue((Integer)(result2.get("replicas")) == replicas);
    
    Map<Integer, String> masterMap1 = new TreeMap<Integer, String>();
    for(String instanceName : masterAssignmentMap1.keySet())
    {
      List<Integer> masterList1 = masterAssignmentMap1.get(instanceName);
      for(Integer partition : masterList1)
      {
        AssertJUnit.assertTrue(!masterMap1.containsKey(partition));
        masterMap1.put(partition, instanceName);
      }
    }
    for(String instanceName : masterAssignmentMap2.keySet())
    {
      List<Integer> masterList2 = masterAssignmentMap2.get(instanceName);
      for(Integer partition : masterList2)
      {
        if(masterMap1.get(partition).equalsIgnoreCase(instanceName))
        {
          commonMasters ++;
        }
      }
    }
    
    System.out.println(commonMasters + " master partitions are kept, "+ (partitions - commonMasters) + " moved, keep ratio:" + 1.0*commonMasters/partitions);
    
    Map<Integer, Set<String>> slaveMap1 = new TreeMap<Integer, Set<String>>();
    for(String instanceName : nodeSlaveAssignmentMap1.keySet())
    {
      Map<String, List<Integer>> slaveAssignment1 = nodeSlaveAssignmentMap1.get(instanceName);
      for(String slaveHostName : slaveAssignment1.keySet())
      {
        List<Integer> slaveList = slaveAssignment1.get(slaveHostName);
        for(Integer partition : slaveList)
        {
          if(!slaveMap1.containsKey(partition))
          {
            slaveMap1.put(partition, new TreeSet<String>());
          }
          AssertJUnit.assertTrue(!slaveMap1.get(partition).contains(slaveHostName));
          slaveMap1.get(partition).add(slaveHostName);
        }
      }
    }
    
    for(String instanceName : nodeSlaveAssignmentMap2.keySet())
    {
      Map<String, List<Integer>> slaveAssignment2 = nodeSlaveAssignmentMap2.get(instanceName);
      for(String slaveHostName : slaveAssignment2.keySet())
      {
        List<Integer> slaveList = slaveAssignment2.get(slaveHostName);
        for(Integer partition : slaveList)
        {
          if(slaveMap1.get(partition).contains(slaveHostName))
          {
            commonSlaves++;
          }
        }
      }
    }
    
    System.out.println(commonSlaves + " slave partitions are kept, " + (partitions * replicas - commonSlaves)+ " moved. keep ratio:"+1.0*commonSlaves/partitions/replicas);
  }
  
}
