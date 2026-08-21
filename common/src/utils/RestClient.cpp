#include "RestClient.h"

#define CPPHTTPLIB_OPENSSL_SUPPORT
#include <httplib.h>
#include <nlohmann/json.hpp>
#include <spdlog/spdlog.h>

#include "AppInfo.h"

std::string RestClient::CheckForUpdates(const std::string &product, const std::string &platform) {
  httplib::Client client("https://meis-apps.com");
  client.set_connection_timeout(5, 0);
  client.set_read_timeout(5, 0);
  client.set_write_timeout(5, 0);
  auto result = client.Get(fmt::format("/rest/checkUpdates?product={}&platform={}&currVersion={}", product, platform, AppInfo::GetVersion()));
  if(!result)
    throw std::runtime_error(to_string(result.error()));
  auto resultJson = nlohmann::json::parse(result->body);
  return resultJson["version"];
}

static httplib::Headers AuthHeaders(const std::string &token) {
  return {{"Authorization", "Bearer " + token}, {"Accept", "application/json"}};
}

std::pair<int, std::string> RestClient::Get(const std::string &baseUrl, const std::string &path, const std::string &bearerToken) {
  httplib::Client client(baseUrl);
  client.enable_server_certificate_verification(true);
  client.set_connection_timeout(5, 0); client.set_read_timeout(10, 0); client.set_write_timeout(10, 0);
  auto result = client.Get(path, AuthHeaders(bearerToken));
  if(!result) return {0, to_string(result.error())};
  return {result->status, result->body};
}

std::pair<int, std::string> RestClient::PostJson(const std::string &baseUrl, const std::string &path, const std::string &bearerToken,
                                                 const std::string &body) {
  httplib::Client client(baseUrl);
  client.enable_server_certificate_verification(true);
  client.set_connection_timeout(5, 0); client.set_read_timeout(10, 0); client.set_write_timeout(10, 0);
  auto result = client.Post(path, AuthHeaders(bearerToken), body, "application/json");
  if(!result) return {0, to_string(result.error())};
  return {result->status, result->body};
}
