#include "CloudUnlockClient.h"
#include <chrono>
#include <nlohmann/json.hpp>
#include <spdlog/spdlog.h>
#include "storage/AppSettings.h"
#include "utils/RestClient.h"

CloudUnlockClient::CloudUnlockClient(const PairedDevice &device) : BaseUnlockConnection(device) {}
bool CloudUnlockClient::Start() {
  if(m_IsRunning) return true;
  if(m_PairedDevice.cloudToken.empty()) { m_UnlockState=UnlockState::NOT_PAIRED_ERROR; return false; }
  m_IsRunning=true; m_AcceptThread=std::thread(&CloudUnlockClient::RelayThread,this); return true;
}
void CloudUnlockClient::Stop() { m_IsRunning=false; if(m_AcceptThread.joinable()) m_AcceptThread.join(); m_HasConnection=false; }
void CloudUnlockClient::RelayThread() {
  try {
    const auto baseUrl=AppSettings::Get().cloudRelayUrl;
    auto request=BuildUnlockRequestJson(); if(!request){m_IsRunning=false;return;}
    auto created=RestClient::PostJson(baseUrl,"/v1/relay/requests",m_PairedDevice.cloudToken,nlohmann::json{{"payload",*request}}.dump());
    if(created.first!=201){spdlog::error("Cloud relay request failed. (HTTP={})",created.first);m_UnlockState=UnlockState::CONNECT_ERROR;m_IsRunning=false;return;}
    auto requestId=nlohmann::json::parse(created.second).at("id").get<std::string>();
    m_HasConnection=true;
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(120);
    while(m_IsRunning&&std::chrono::steady_clock::now()<deadline){
      auto response=RestClient::Get(baseUrl,"/v1/relay/requests/"+requestId+"/response",m_PairedDevice.cloudToken);
      if(response.first==200){ProcessUnlockResponseJson(nlohmann::json::parse(response.second).at("payload").get<std::string>());m_IsRunning=false;return;}
      if(response.first!=204){m_UnlockState=UnlockState::CONNECT_ERROR;m_IsRunning=false;return;}
      std::this_thread::sleep_for(std::chrono::milliseconds(750));
    }
    if(m_UnlockState==UnlockState::UNKNOWN)m_UnlockState=UnlockState::TIMEOUT;
  }catch(const std::exception &ex){spdlog::error("Cloud relay error: {}",ex.what());m_UnlockState=UnlockState::DATA_ERROR;}
  m_IsRunning=false;
}
