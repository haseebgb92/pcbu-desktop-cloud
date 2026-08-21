#ifndef PCBU_DESKTOP_RESTCLIENT_H
#define PCBU_DESKTOP_RESTCLIENT_H

#include <cstdint>
#include <string>
#include <utility>

class RestClient {
public:
  static std::string CheckForUpdates(const std::string &product, const std::string &platform);
  static std::pair<int, std::string> Get(const std::string &baseUrl, const std::string &path, const std::string &bearerToken);
  static std::pair<int, std::string> PostJson(const std::string &baseUrl, const std::string &path, const std::string &bearerToken,
                                              const std::string &body);

private:
  RestClient() = default;
};

#endif // PCBU_DESKTOP_RESTCLIENT_H
