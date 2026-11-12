void ConnectionSocket::openConnection(std::string address, uint16_t port, std::string secret, bool ipv6, int32_t networkType) {
    currentNetworkType = networkType;
    isIpv6 = ipv6;
    currentAddress = address;
    currentPort = port;
    waitingForHostResolve.clear();
    adjustWriteOpAfterResolve = false;
    tlsState = 0;
    proxyAuthState = 0;
    currentSecret.clear();
    currentSecretDomain.clear();
    ConnectionsManager::getInstance(instanceNum).attachConnection(this);

    if (!FamilyTransportReady()) {
        closeSocket(1, EACCES);
        return;
    }
    std::string *proxyAddress = &overrideProxyAddress;
    std::string *proxySecret = &overrideProxySecret;
    uint16_t proxyPort = overrideProxyPort;
    if (proxyAddress->empty()) {
        proxyAddress = &ConnectionsManager::getInstance(instanceNum).proxyAddress;
        proxyPort = ConnectionsManager::getInstance(instanceNum).proxyPort;
        proxySecret = &ConnectionsManager::getInstance(instanceNum).proxySecret;
    }

    // Optional Telegram SOCKS/MTProxy is itself reached inside the family tunnel.
    // Resolution happens in the Go transport; no Java/local DNS escape is needed.
    const bool useProxy = !proxyAddress->empty();
    const std::string &endpoint = useProxy ? *proxyAddress : address;
    const uint16_t endpointPort = useProxy ? proxyPort : port;
    socketFd = FamilyTransportDial(endpoint.c_str(), endpointPort);
    if (socketFd < 0) {
        closeSocket(1, ECONNREFUSED);
        return;
    }

    const std::string &transportSecret = useProxy ? *proxySecret : secret;
    uint32_t tempBuffLength = 0;
    if (useProxy && transportSecret.empty()) {
        proxyAuthState = 1;
        tempBuffLength = 1024;
    } else if (transportSecret.size() > 17 && transportSecret[0] == '\xee') {
        proxyAuthState = 10;
        currentSecret = transportSecret.substr(1, 16);
        currentSecretDomain = transportSecret.substr(17);
        tempBuffLength = 65 * 1024;
    }
    if (tempBuffLength > 0 && (tempBuffer == nullptr || tempBuffer->length < tempBuffLength)) {
        delete tempBuffer;
        tempBuffer = new ByteArray(tempBuffLength);
    }
    openConnectionInternal(ipv6);
}

void ConnectionSocket::openConnectionInternal(bool ipv6) {
    (void) ipv6;
    // FGDial already returns a connected AF_UNIX stream, O_NONBLOCK/CLOEXEC.
    // TCP connect and IP-specific socket options are invalid and forbidden here.
    if (socketFd < 0 || !FamilyTransportReady()) {
        closeSocket(1, EACCES);
        return;
    }
    int flags = fcntl(socketFd, F_GETFL, 0);
    if (flags < 0 || !(flags & O_NONBLOCK)) {
        closeSocket(1, EINVAL);
        return;
    }
    eventMask.events = EPOLLOUT | EPOLLIN | EPOLLRDHUP | EPOLLERR | EPOLLET;
    eventMask.data.ptr = eventObject;
    int epolFd = ConnectionsManager::getInstance(instanceNum).epolFd;
    if (epoll_ctl(epolFd, EPOLL_CTL_ADD, socketFd, &eventMask) != 0) {
        closeSocket(1, errno);
        return;
    }
    if (adjustWriteOpAfterResolve) adjustWriteOp();
}
