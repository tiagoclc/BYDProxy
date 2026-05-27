function FindProxyForURL(url, host) {
    // 1. Filtro rápido de texto (Sem resolver DNS): se for nome simples ou IP da rede local do carro, vai direto
    if (isPlainHostName(host) ||
        shExpMatch(host, "*.local") ||
        shExpMatch(host, "192.168.*") ||
        shExpMatch(host, "127.0.0.1") ||
        shExpMatch(host, "localhost")) {
        return "DIRECT";
    }

    // 2. Para tudo o resto (WhatsApp, Telegram, YouTube), joga às cegas para o SOCKS5 na porta 8888
    return "SOCKS5 192.168.43.1:8888; SOCKS 192.168.43.1:8888; DIRECT";
}