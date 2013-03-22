/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2013 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2013 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.alarmd.northbounder.syslog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opennms.core.utils.LogUtils;
import org.opennms.core.utils.PropertiesUtils;
import org.opennms.netmgt.alarmd.api.NorthboundAlarm;
import org.opennms.netmgt.alarmd.api.NorthboundAlarm.AlarmType;
import org.opennms.netmgt.alarmd.api.NorthboundAlarm.x733ProbableCause;
import org.opennms.netmgt.alarmd.api.NorthbounderException;
import org.opennms.netmgt.alarmd.api.support.AbstractNorthbounder;
import org.opennms.netmgt.alarmd.northbounder.syslog.SyslogDestination.SyslogFacility;
import org.opennms.netmgt.alarmd.northbounder.syslog.SyslogDestination.SyslogProtocol;
import org.opennms.netmgt.dao.NodeDao;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsSeverity;
import org.productivity.java.syslog4j.Syslog;
import org.productivity.java.syslog4j.SyslogConfigIF;
import org.productivity.java.syslog4j.SyslogConstants;
import org.productivity.java.syslog4j.SyslogIF;
import org.productivity.java.syslog4j.SyslogRuntimeException;
import org.productivity.java.syslog4j.impl.net.tcp.TCPNetSyslogConfig;
import org.productivity.java.syslog4j.impl.net.udp.UDPNetSyslogConfig;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Forwards alarms, N, via Syslog.
 * 
 * @author <a href="mailto:david@opennms.org>David Hustace</a>
 */
public class SyslogNorthbounder extends AbstractNorthbounder implements InitializingBean {
	
	@Autowired
    private SyslogNorthbounderConfig m_config;
	
	private NodeDao m_nodeDao;
	
	
    private Object m_configLock = new Object();

    public SyslogNorthbounder() {
        super("SyslogNorthbounder");
        
    }

    public SyslogNorthbounder(SyslogNorthbounderConfig config) {
    	super("SyslogNorthbounder");
    	m_config = config;
    }
    
	@Override
	public void afterPropertiesSet() throws Exception {
		
		if (m_config == null) {
			String msg = "Syslog forwarding configuration is not initialized.";
			IllegalStateException e = new IllegalStateException(msg);
			LogUtils.errorf(this, e, msg);
			throw e;
		}
		
		
		createNorthboundInstances();
		setNaglesDelay(m_config.getNaglesDelay());
		setMaxBatchSize(m_config.getBatchSize());
		setMaxPreservedAlarms(m_config.getAlarmQueueSize());
	}

	/**
     * The abstraction makes a call here to determine if the alarm should be placed
     * on the queue of alarms to be sent northerly.
     * 
     */
	@Override
    public boolean accepts(NorthboundAlarm alarm) {
		
		LogUtils.debugf(this, "Validating UEI of alarm: %s", alarm.getUei());
		
        if (getConfig().getUeis() == null || getConfig().getUeis().contains(alarm.getUei())) {
    		LogUtils.debugf(this, "UEI: %s, accepted.", alarm.getUei());
            return true;
        }
        
		LogUtils.debugf(this, "UEI: %s, rejected.", alarm.getUei());
        return false;
    }
    
	/**
	 * Each implementation of the AbstractNorthbounder has a nice queue (Nagle's algorithmic) and the worker
	 * thread that processes the queue calls this method to send alarms to the northern NMS.
	 * 
	 */
    @Override
    public void forwardAlarms(List<NorthboundAlarm> alarms) throws NorthbounderException {
        
        List<SyslogDestination> dests = getDestinations();
        
        if (dests == null) {
        	String errorMsg = "No Syslog destinations are defined.";
			IllegalStateException e = new IllegalStateException(errorMsg);
        	LogUtils.errorf(this, e, errorMsg);
			throw e;
        }
        
        if (alarms == null) {
        	String errorMsg = "No alarms in alarms list for syslog forwarding.";
			IllegalStateException e = new IllegalStateException(errorMsg);
        	LogUtils.errorf(this, e, errorMsg);
			throw e;
        }
        
        LogUtils.infof(this, "Forwarding %d alarms to %d destinations...", alarms.size(), dests.size());

    	Map<Integer, Map<String, String>> alarmMappings = new HashMap<Integer, Map<String, String>>();    	
        
        for (SyslogDestination dest : dests) {
        	
        	SyslogIF instance;
			try {
				instance = Syslog.getInstance(dest.getName());
			} catch (SyslogRuntimeException e) {
				LogUtils.errorf(this, e, "Could not find Syslog instance for destination: %s.", dest.getName());
				continue;
			}
        	
        	LogUtils.debugf(this, "Forwarding alarms to destination %s.", dest.getName());
        	
        	/*
        	 * Iterate over the list of alarms to be forwarded N.
        	 */
        	for (NorthboundAlarm alarm : alarms) {

        		LogUtils.debugf(this, "Creating formatted log message for alarm: %d.", alarm.getId());
        		
        		Map<String, String> mapping = null;
        		
        		@SuppressWarnings("unchecked")
				String syslogMessage;
				int level;
				try {
					if (alarmMappings != null) {
						mapping = alarmMappings.get(alarm.getId());
					}
					
					if (mapping == null) {
						mapping = createMapping(alarmMappings, alarm);
					}
					
					LogUtils.debugf(this, "Making substitutions for tokens in message format for alarm: %d.", alarm.getId());
					syslogMessage = PropertiesUtils.substitute(m_config.getMessageFormat(), mapping);

					LogUtils.debugf(this, "Determining LOG_LEVEL for alarm: %d", alarm.getId());
					level = determineLogLevel(alarm.getSeverity());
	        		//Send the Syslog message...
					LogUtils.debugf(this, "Forwarding alarm: %d via syslog to destination: %s", alarm.getId(), dest.getName());
					instance.log(level, syslogMessage);
				} catch (Exception e1) {
					LogUtils.errorf(this, e1, "Caught exception sending to destination: %s", dest.getName());
				}

        	}
        }

    }
    

	private List<SyslogDestination> getDestinations() {
		return getConfig().getDestinations();
	}

	private Map<String, String> createMapping(
			Map<Integer, Map<String, String>> alarmMappings,
			NorthboundAlarm alarm) {
		Map<String, String> mapping;
		mapping = new HashMap<String, String>();
		mapping.put("ackUser", alarm.getAckUser());
		mapping.put("appDn", alarm.getAppDn());
		mapping.put("logMsg", alarm.getLogMsg());
		mapping.put("objectInstance", alarm.getObjectInstance());
		mapping.put("objectType", alarm.getObjectType());
		mapping.put("ossKey", alarm.getOssKey());
		mapping.put("ossState", alarm.getOssState());
		mapping.put("ticketId", alarm.getTicketId());
		mapping.put("alarmUei", alarm.getUei());
		mapping.put("ackTime", nullSafeToString(alarm.getAckTime(), ""));
		
		AlarmType alarmType = alarm.getAlarmType() == null ? AlarmType.NOTIFICATION : alarm.getAlarmType();
		mapping.put("alarmType", alarmType.name());
		
		String count = alarm.getCount() == null ? "1" : alarm.getCount().toString();
		mapping.put("count", count);
		
		mapping.put("firstOccurrence", nullSafeToString(alarm.getFirstOccurrence(), ""));
		mapping.put("alarmId", alarm.getId().toString());
		mapping.put("ipAddr", nullSafeToString(alarm.getIpAddr(), ""));
		mapping.put("lastOccurrence", nullSafeToString(alarm.getLastOccurrence(), ""));
		
		
		if (alarm.getNodeId() != null) {
			mapping.put("nodeId", alarm.getNodeId().toString());

			//Implement this so we don't have load the entire node.
			//m_nodeDao.getLabelForId();
			
			OnmsNode node = m_nodeDao.get(alarm.getNodeId());
			mapping.put("nodeLabel", node.getLabel());
		} else {
			mapping.put("nodeId", "");
			mapping.put("nodeLabel", "");
		}
		
		
		String poller = alarm.getPoller() == null ? "localhost" : alarm.getPoller().getName();
		mapping.put("distPoller", poller);
		
		String service = alarm.getService() == null ? "" : alarm.getService().getName();					
		mapping.put("ifService", service);
		
		mapping.put("severity", nullSafeToString(alarm.getSeverity(), ""));
		mapping.put("ticketState", nullSafeToString(alarm.getTicketState(), ""));
		
		mapping.put("x733AlarmType", alarm.getX733Type());
		
		try {
			mapping.put("x733ProbableCause", nullSafeToString(x733ProbableCause.get(alarm.getX733Cause()), ""));
		} catch (Exception e) {
			LogUtils.infof(this, e, "Exception caught setting X733 Cause: %d", alarm.getX733Cause());
			mapping.put("x733ProbableCause", "");
		}
		
		alarmMappings.put(alarm.getId(), mapping);
		return mapping;
	}

	private String nullSafeToString(Object obj, String defaultString) {
		if (obj != null) {
			defaultString = obj.toString();
		}
		return defaultString;
	}

    
    /**
     * This is here, for now, until it can be properly wired and proper configuration can be created.
     * This allows generic 127.0.0.1:UDP/514 to work with OpenNMS having no configuration.  This is
     * trickery in its finest hour.
     */
    private void createNorthboundInstances() throws SyslogRuntimeException {
    	
    	LogUtils.infof(this, "Creating Syslog Northbound Instances...");
    	
    	LogUtils.debugf(this, "Acquiring configuration lock...");
    	synchronized (m_configLock) {
    		
    		LogUtils.debugf(this, "Lock acquired.");
			
			List<SyslogDestination> destinations = m_config.getDestinations();
			for (SyslogDestination dest : destinations) {

				String instName = dest.getName();
				int facility = convertFacility(dest.getFacility());
				SyslogProtocol protocol = dest.getProtocol();
				SyslogConfigIF instanceConfiguration = createConfig(dest, protocol, facility);
				instanceConfiguration.setIdent("OpenNMS");
				instanceConfiguration.setCharSet(dest.getCharSet());
				instanceConfiguration.setMaxMessageLength(dest.getMaxMessageLength());
				instanceConfiguration.setSendLocalName(dest.isSendLocalName());
				instanceConfiguration.setSendLocalTimestamp(dest.isSendLocalTime());
				instanceConfiguration.setTruncateMessage(dest.isTruncateMessage());
				instanceConfiguration.setUseStructuredData(SyslogConstants.USE_STRUCTURED_DATA_DEFAULT);

				LogUtils.debugf(this, "Creating northbound syslog instance: %s", instName);
				try {
					Syslog.createInstance(instName, instanceConfiguration);
				} catch (SyslogRuntimeException e) {
					String msg = "Could not create northbound instance, %s";
					LogUtils.errorf(this, e, msg, instName);
					throw e;
				}

			}
		}
    	
	}

	private SyslogConfigIF createConfig(SyslogDestination dest,
			SyslogProtocol protocol, int fac) {
		SyslogConfigIF config;
		switch (protocol) {
		case UDP:
			config = new UDPNetSyslogConfig(fac, dest.getHost(), dest.getPort());
			break;
		case TCP:
			config = new TCPNetSyslogConfig(fac, dest.getHost(), dest.getPort());
			break;
		default:
			config = new UDPNetSyslogConfig(fac, "localhost", 514);
		}
		return config;
	}

	private int convertFacility(SyslogFacility facility) {
		int fac;
		switch (facility) {
		case KERN:
			fac = SyslogConstants.FACILITY_KERN;
			break;
		case USER:
			fac = SyslogConstants.FACILITY_USER;
			break;
		case MAIL:
			fac = SyslogConstants.FACILITY_MAIL;
			break;
		case DAEMON:
			fac = SyslogConstants.FACILITY_DAEMON;
			break;
		case AUTH:
			fac = SyslogConstants.FACILITY_AUTH;
			break;
		case SYSLOG:
			fac = SyslogConstants.FACILITY_SYSLOG;
			break;
		case LPR:
			fac = SyslogConstants.FACILITY_LPR;
			break;
		case NEWS:
			fac = SyslogConstants.FACILITY_NEWS;
			break;
		case UUCP:
			fac = SyslogConstants.FACILITY_UUCP;
			break;
		case CRON:
			fac = SyslogConstants.FACILITY_CRON;
			break;
		case AUTHPRIV:
			fac = SyslogConstants.FACILITY_AUTHPRIV;
			break;
		case FTP:
			fac = SyslogConstants.FACILITY_FTP;
			break;
		case LOCAL0:
			fac = SyslogConstants.FACILITY_LOCAL0;
			break;
		case LOCAL1:
			fac = SyslogConstants.FACILITY_LOCAL1;
			break;
		case LOCAL2:
			fac = SyslogConstants.FACILITY_LOCAL2;
			break;
		case LOCAL3:
			fac = SyslogConstants.FACILITY_LOCAL3;
			break;
		case LOCAL4:
			fac = SyslogConstants.FACILITY_LOCAL4;
			break;
		case LOCAL5:
			fac = SyslogConstants.FACILITY_LOCAL5;
			break;
		case LOCAL6:
			fac = SyslogConstants.FACILITY_LOCAL6;
			break;
		case LOCAL7:
			fac = SyslogConstants.FACILITY_LOCAL7;
			break;
		default:
			fac = SyslogConstants.FACILITY_USER;
		}
		return fac;
	}

	private int determineLogLevel(OnmsSeverity severity) {
		int level;
		switch (severity) {
		case CRITICAL:
			level = SyslogConstants.LEVEL_CRITICAL;
			break;
		case MAJOR:
			level = SyslogConstants.LEVEL_ERROR;
			break;
		case MINOR:
			level = SyslogConstants.LEVEL_ERROR;
			break;
		case WARNING:
			level = SyslogConstants.LEVEL_WARN;
		case NORMAL:
			level = SyslogConstants.LEVEL_NOTICE;
			break;
		case CLEARED:
			level = SyslogConstants.LEVEL_INFO;
			break;
		case INDETERMINATE:
			level = SyslogConstants.LEVEL_DEBUG;
			break;
		default:
			level = SyslogConstants.LEVEL_WARN;
		}
		return level;
	}


    public SyslogNorthbounderConfig getConfig() {
    	
    	if (m_config == null) {
//    		LogUtils.errorf(this, "Syslog Northbounder configuration is not set.", null);
    		throw new IllegalStateException("Syslog Northbound configuration not set.");
    	}
        return m_config;
    }

    public void setConfig(SyslogNorthbounderConfig config) {
    	if (config == null) {
    		throw new IllegalStateException("Configuration cannot be set to null!");
    	}
    	
		synchronized (m_configLock) {
			m_config = config;
    		Syslog.shutdown();
    		Syslog.initialize();
    		createNorthboundInstances();
		}
    }

	public NodeDao getNodeDao() {
		return m_nodeDao;
	}

	public void setNodeDao(NodeDao nodeDao) {
		m_nodeDao = nodeDao;
	}

}