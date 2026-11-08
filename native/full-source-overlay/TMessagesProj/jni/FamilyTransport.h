#pragma once
bool FamilyTransportLoad();
bool FamilyTransportReady();
int FamilyTransportDial(const char *host, int port);
void FamilyTransportRelease(int fd);
int FamilyTransportStart(const char *profile);
void FamilyTransportStop();
int FamilyTransportRenew(int seconds);
const char *FamilyTransportError();
