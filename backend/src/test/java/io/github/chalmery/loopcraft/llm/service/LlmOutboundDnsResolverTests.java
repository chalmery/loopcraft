package io.github.chalmery.loopcraft.llm.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmOutboundDnsResolverTests {
    @Test
    void rejectsPrivateReservedAndEmbeddedPrivateAddressesWithoutExternalDns() throws Exception {
        var resolver = new LlmOutboundDnsResolver("");
        for (String ip : new String[] {"0.0.0.0", "127.0.0.1", "127.1", "2130706433", "10.2.3.4",
                "172.16.0.1", "192.168.0.1", "100.100.100.200", "169.254.169.254", "224.0.0.1", "255.255.255.255",
                "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "168.63.129.16",
                "::", "::1", "fc00::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1",
                "2002:7f00:1::", "2001::1", "2001:db8::1", "3fff::1"}) {
            assertThatThrownBy(() -> resolver.resolve(ip)).as(ip).isInstanceOf(UnknownHostException.class);
        }
        assertThat(resolver.resolve("8.8.8.8")).containsExactly(InetAddress.getByName("8.8.8.8"));
        assertThat(resolver.resolve("2606:4700:4700::1111")).hasSize(1);
    }

    @Test
    void rejectsMixedAnswersAndDoesNotTrustEarlierPublicResolution() throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        when(dns.resolve("provider.test")).thenReturn(new InetAddress[] {InetAddress.getByName("8.8.8.8")},
                new InetAddress[] {InetAddress.getByName("127.0.0.1")},
                new InetAddress[] {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")});
        var resolver = new LlmOutboundDnsResolver("", dns);
        // 返回的是已解析的套接字地址，HTTP 客户端不能再次查询原域名。
        assertThat(resolver.resolve("provider.test", 443).getFirst().isUnresolved()).isFalse();
        assertThatThrownBy(() -> resolver.resolve("provider.test", 443)).isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> resolver.resolve("provider.test", 443)).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void internalHostsRequireExactDeploymentAllowlistAndKeepResolvedAddresses() throws Exception {
        DnsResolver dns = mock(DnsResolver.class);
        var loopback = InetAddress.getByName("127.0.0.1");
        when(dns.resolve("internal.test")).thenReturn(new InetAddress[] {loopback});
        when(dns.resolve("other.internal.test")).thenReturn(new InetAddress[] {loopback});
        var resolver = new LlmOutboundDnsResolver(" INTERNAL.TEST. ", dns);
        assertThat(resolver.resolve("internal.test", 80).getFirst().getAddress()).isSameAs(loopback);
        assertThatThrownBy(() -> resolver.resolve("other.internal.test")).isInstanceOf(UnknownHostException.class);
        assertThat(new LlmOutboundDnsResolver("[::1]").resolve("::1")).hasSize(1);
    }
}
