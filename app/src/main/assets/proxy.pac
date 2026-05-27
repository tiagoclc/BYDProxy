function FindProxyForURL(url, host) {
    // Se o host for local, não usa proxy
    if (isPlainHostName(host) ||
        shExpMatch(host, "*.local") ||
        isInNet(dnsResolve(host), "10.0.0.0", "255.0.0.0") ||
        isInNet(dnsResolve(host), "172.16.0.0", "255.240.0.0") ||
        isInNet(dnsResolve(host), "192.168.0.0", "255.255.0.0") ||
        isInNet(dnsResolve(host), "127.0.0.0", "255.255.255.0")) {
        return "DIRECT";
    }

    // Caso contrário, usa o nosso Proxy (HTTP ou SOCKS5)
    // O IP 192.168.43.1 é o padrão do hotspot Android, mas o PAC tentará o proxy primeiro
    return "SOCKS5 192.168.43.1:8888; PROXY 192.168.43.1:8888; DIRECT";
}