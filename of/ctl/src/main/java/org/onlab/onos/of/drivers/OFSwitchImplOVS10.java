package org.onlab.onos.of.drivers;

import java.util.List;

import org.onlab.onos.of.controller.Dpid;
import org.onlab.onos.of.controller.driver.AbstractOpenFlowSwitch;
import org.projectfloodlight.openflow.protocol.OFDescStatsReply;
import org.projectfloodlight.openflow.protocol.OFMessage;

/**
 * OFDescriptionStatistics Vendor (Manufacturer Desc.): Nicira, Inc. Make
 * (Hardware Desc.) : Open vSwitch Model (Datapath Desc.) : None Software :
 * 1.11.90 (or whatever version + build) Serial : None
 */
public class OFSwitchImplOVS10 extends AbstractOpenFlowSwitch {

    public OFSwitchImplOVS10(Dpid dpid, OFDescStatsReply desc) {
        super(dpid);
        setSwitchDescription(desc);

    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "OFSwitchImplOVS10 [" + ((channel != null)
                ? channel.getRemoteAddress() : "?")
                + " DPID[" + ((getStringId() != null) ? getStringId() : "?") + "]]";
    }

    @Override
    public Boolean supportNxRole() {
        return true;
    }

    @Override
    public void startDriverHandshake() {}

    @Override
    public boolean isDriverHandshakeComplete() {
        return true;
    }

    @Override
    public void processDriverHandshakeMessage(OFMessage m) {}

    @Override
    public void write(OFMessage msg) {
        channel.write(msg);

    }

    @Override
    public void write(List<OFMessage> msgs) {
        channel.write(msgs);
    }
}