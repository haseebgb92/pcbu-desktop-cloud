#ifndef PCBU_CLOUDUNLOCKCLIENT_H
#define PCBU_CLOUDUNLOCKCLIENT_H
#include "connection/unlock/BaseUnlockConnection.h"
class CloudUnlockClient : public BaseUnlockConnection {
public:
  explicit CloudUnlockClient(const PairedDevice &device);
  bool Start() override;
  void Stop() override;
private:
  void RelayThread();
};
#endif
