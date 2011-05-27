package org.opennms.netmgt.threshd;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.Map;

import org.opennms.core.utils.ThreadCategory;
import org.opennms.netmgt.EventConstants;
import org.opennms.netmgt.xml.event.Event;
import org.opennms.netmgt.xml.event.Parm;
import org.opennms.netmgt.xml.event.Parms;
import org.opennms.netmgt.xml.event.Value;

/**
 * <p>Abstract AbstractThresholdEvaluatorState class.</p>
 *
 * @author ranger
 * @version $Id: $
 */
public abstract class AbstractThresholdEvaluatorState implements ThresholdEvaluatorState {

    private static final String UNKNOWN = "Unknown";

    /**
     * <p>createBasicEvent</p>
     *
     * @param uei a {@link java.lang.String} object.
     * @param date a {@link java.util.Date} object.
     * @param dsValue a double.
     * @param resource a {@link org.opennms.netmgt.threshd.CollectionResourceWrapper} object.
     * @param additionalParams a {@link java.util.Map} object.
     * @return a {@link org.opennms.netmgt.xml.event.Event} object.
     */
    protected Event createBasicEvent(String uei, Date date, double dsValue, CollectionResourceWrapper resource, Map<String,String> additionalParams) {
        if (resource == null) { // Still works, mimic old code when instance value is null.
            resource = new CollectionResourceWrapper(date, 0, null, null, null, null, null);
        }
        String dsLabelValue = resource.getLabelValue(resource.getLabel());
        if (dsLabelValue == null) dsLabelValue = UNKNOWN;

        // create the event to be sent
        Event event = new Event();
        event.setUei(uei);
        event.setNodeid(resource.getNodeId());
        event.setService(resource.getServiceName());

        // As a suggestion from Bug2711. Host Address will contain Interface IP Address for Interface Resource
        event.setInterface(resource.getHostAddress());            

        Parms eventParms = new Parms();

        if (resource.isAnInterfaceResource()) {
            // Update threshold label if it is unknown. This is useful because usually reduction-key is associated to label parameter
            if (UNKNOWN.equals(dsLabelValue))
                dsLabelValue = resource.getIfLabel();
            // Set interface specific parameters
            addEventParm(eventParms, "ifLabel", resource.getIfLabel());
            addEventParm(eventParms, "ifIndex", resource.getIfIndex());
            String ipaddr = resource.getIfInfoValue("ipaddr");
            if (ipaddr != null && !"0.0.0.0".equals(ipaddr))
                addEventParm(eventParms, "ifIpAddress", ipaddr);
        }

        // Set resource label
        addEventParm(eventParms, "label", dsLabelValue);

        // set the source of the event to the datasource name
        event.setSource("OpenNMS.Threshd." + getThresholdConfig().getDatasourceExpression());

        // Set event host
        try {
            event.setHost(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            event.setHost("unresolved.host");
            log().warn("Failed to resolve local hostname: " + e, e);
        }

        // Set event time
        event.setTime(EventConstants.formatToString(date));

        // Add datasource name
        addEventParm(eventParms, "ds", getThresholdConfig().getDatasourceExpression());
        
        // Add last known value of the datasource fetched from its RRD file
        addEventParm(eventParms, "value", formatValue(dsValue));

        // Add the instance name of the resource in question
        addEventParm(eventParms, "instance", resource.getInstance() != null ? resource.getInstance() : "null");

        // Add additional parameters
        if (additionalParams != null) {
            for (String p : additionalParams.keySet()) {
                addEventParm(eventParms, p, additionalParams.get(p));
            }
        }

        // Add Parms to the event
        event.setParms(eventParms);
        
        return event;
    }

    /**
     * <p>formatValue</p>
     *
     * @param value a {@link java.lang.Double} object.
     * @return a {@link java.lang.String} object.
     */
    protected String formatValue(Double value) {
        String pattern = System.getProperty("org.opennms.threshd.value.decimalformat", "###.##");
        DecimalFormat valueFormatter = new DecimalFormat(pattern);
        return valueFormatter.format(value);
    }

    private void addEventParm(Parms parms, String key, String value) {
        if (value !=  null) {
            Parm eventParm = new Parm();
            eventParm.setParmName(key);
            Value parmValue = new Value();
            parmValue.setContent(value);
            eventParm.setValue(parmValue);
            parms.addParm(eventParm);
        }
    }
    
    /**
     * <p>log</p>
     *
     * @return a {@link org.opennms.core.utils.ThreadCategory} object.
     */
    protected final ThreadCategory log() {
        return ThreadCategory.getInstance(getClass());
    }

}
