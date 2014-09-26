package org.onlab.packet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;
import org.onlab.packet.IpPrefix.Version;

import com.google.common.testing.EqualsTester;

public class IpPrefixTest {

    private static final byte [] BYTES1 = new byte [] {0xa, 0x0, 0x0, 0xa};
    private static final byte [] BYTES2 = new byte [] {0xa, 0x0, 0x0, 0xb};
    private static final int INTVAL1 = 167772170;
    private static final int INTVAL2 = 167772171;
    private static final String STRVAL = "10.0.0.12";
    private static final int MASK = 16;

    @Test
    public void testEquality() {
        IpPrefix ip1 = IpPrefix.valueOf(BYTES1);
        IpPrefix ip2 = IpPrefix.valueOf(INTVAL1);
        IpPrefix ip3 = IpPrefix.valueOf(BYTES2);
        IpPrefix ip4 = IpPrefix.valueOf(INTVAL2);
        IpPrefix ip5 = IpPrefix.valueOf(STRVAL);

        new EqualsTester().addEqualityGroup(ip1, ip2)
        .addEqualityGroup(ip3, ip4)
        .addEqualityGroup(ip5)
        .testEquals();

        // string conversions
        IpPrefix ip6 = IpPrefix.valueOf(BYTES1, MASK);
        IpPrefix ip7 = IpPrefix.valueOf("10.0.0.10/16");
        IpPrefix ip8 = IpPrefix.valueOf(new byte [] {0xa, 0x0, 0x0, 0xc});
        assertEquals("incorrect address conversion", ip6, ip7);
        assertEquals("incorrect address conversion", ip5, ip8);
    }

    @Test
    public void basics() {
        IpPrefix ip1 = IpPrefix.valueOf(BYTES1, MASK);
        final byte [] bytes = new byte [] {0xa, 0x0, 0x0, 0xa};

        //check fields
        assertEquals("incorrect IP Version", Version.INET, ip1.version());
        assertEquals("incorrect netmask", 16, ip1.netmask);
        assertTrue("faulty toOctets()", Arrays.equals(bytes, ip1.toOctets()));
        assertEquals("faulty toInt()", INTVAL1, ip1.toInt());
        assertEquals("faulty toString()", "10.0.0.10/16", ip1.toString());
    }

    @Test
    public void netmasks() {
        // masked
        IpPrefix ip1 = IpPrefix.valueOf(BYTES1, MASK);

        IpPrefix host = IpPrefix.valueOf("0.0.0.10/16");
        IpPrefix network = IpPrefix.valueOf("10.0.0.0/16");
        assertEquals("incorrect host address", host, ip1.host());
        assertEquals("incorrect network address", network, ip1.network());
        assertEquals("incorrect netmask", "255.255.0.0", ip1.netmask().toString());

        //unmasked
        IpPrefix ip2 = IpPrefix.valueOf(BYTES1);
        IpPrefix umhost = IpPrefix.valueOf("10.0.0.10/0");
        IpPrefix umnet = IpPrefix.valueOf("0.0.0.0/0");
        assertEquals("incorrect host address", umhost, ip2.host());
        assertEquals("incorrect host address", umnet, ip2.network());
        assertTrue("incorrect netmask",
                Arrays.equals(IpPrefix.ANY, ip2.netmask().toOctets()));
    }

    @Test
    public void testContains() {
        IpPrefix slash31 = IpPrefix.valueOf(BYTES1, 31);
        IpPrefix slash32 = IpPrefix.valueOf(BYTES1, 32);
        IpPrefix differentSlash32 = IpPrefix.valueOf(BYTES2, 32);

        assertTrue(slash31.contains(differentSlash32));
        assertFalse(differentSlash32.contains(slash31));

        assertTrue(slash31.contains(slash32));
        assertFalse(slash32.contains(differentSlash32));
        assertFalse(differentSlash32.contains(slash32));

        IpPrefix zero = IpPrefix.valueOf("0.0.0.0/0");
        assertTrue(zero.contains(differentSlash32));
        assertFalse(differentSlash32.contains(zero));

        IpPrefix slash8 = IpPrefix.valueOf("10.0.0.0/8");
        assertTrue(slash8.contains(slash31));
        assertFalse(slash31.contains(slash8));
    }
}