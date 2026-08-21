#ifndef PCBU_DESKTOP_CLOUDCOMMANDLISTENER_H
#define PCBU_DESKTOP_CLOUDCOMMANDLISTENER_H

#include <atomic>
#include <thread>

class CloudCommandListener {
public:
  CloudCommandListener();
  ~CloudCommandListener();
  CloudCommandListener(const CloudCommandListener &) = delete;
  CloudCommandListener &operator=(const CloudCommandListener &) = delete;

private:
  void Run();
  std::atomic<bool> m_Running{true};
  std::thread m_Thread;
};

#endif
