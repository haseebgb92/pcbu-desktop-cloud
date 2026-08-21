#include "CloudCommandListener.h"

#include <chrono>
#include <nlohmann/json.hpp>
#include <spdlog/spdlog.h>

#ifdef WINDOWS
#include <Windows.h>
#endif

#include "storage/AppSettings.h"
#include "storage/PairedDevicesStorage.h"
#include "utils/CryptUtils.h"
#include "utils/RestClient.h"
#include "utils/StringUtils.h"

CloudCommandListener::CloudCommandListener() : m_Thread(&CloudCommandListener::Run, this) {}

CloudCommandListener::~CloudCommandListener() {
  m_Running = false;
  if(m_Thread.joinable()) m_Thread.join();
}

void CloudCommandListener::Run() {
  while(m_Running) {
    for(const auto &device : PairedDevicesStorage::GetDevices()) {
      if(!m_Running || device.pairingMethod != PairingMethod::CLOUD_TCP || device.cloudToken.empty()) continue;
      try {
        const auto response = RestClient::Get(AppSettings::Get().cloudRelayUrl, "/v1/relay/commands", device.cloudToken);
        if(response.first != 200) continue;
        const auto wrapper = nlohmann::json::parse(response.second);
        const auto payload = nlohmann::json::parse(wrapper.at("payload").get<std::string>());
        if(payload.value("deviceId", "") != device.id) continue;
        const auto decrypted = CryptUtils::DecryptAESPacket(StringUtils::FromHexString(payload.at("encData").get<std::string>()), device.encryptionKey);
        if(decrypted.result != PacketCryptResult::OK) continue;
        const auto command = nlohmann::json::parse(std::string(decrypted.data.begin(), decrypted.data.end()));
        const auto action = command.value("action", "");
        if(action == "LOCK" && command.value("deviceId", "") == device.id) {
#ifdef WINDOWS
          if(!LockWorkStation()) spdlog::error("Remote LockWorkStation failed. (Code={})", GetLastError());
#endif
        } else if(action == "PREPARE_UNLOCK" && command.value("deviceId", "") == device.id) {
#ifdef WINDOWS
          // Wake the monitor so LogonUI can enumerate the credential provider. Do not
          // synthesize input: SendInput cannot safely cross Windows' secure desktop.
          SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED);
          SendMessageTimeoutW(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, static_cast<LPARAM>(-1),
                              SMTO_ABORTIFHUNG, 1000, nullptr);
#endif
        }
      } catch(const std::exception &ex) {
        spdlog::debug("Cloud command poll failed: {}", ex.what());
      }
    }
    for(int i=0;i<10 && m_Running;i++) std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }
}
