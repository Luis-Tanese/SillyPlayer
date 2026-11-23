#pragma once
#include <QByteArray>
#include <QByteArrayView>
#include <QIODevice>
#include <array>
#include <memory>
#include <openssl/evp.h>
#include <stdexcept>

// preserve SHA-256 content identities while using OpenSSL's accelerated implementation for multi-gigabyte archives and media
// EVP state is tiny and device reads and callers' transfer buffers remain bounded
class ContentHash {
public:
  ContentHash() : context(EVP_MD_CTX_new(), EVP_MD_CTX_free) {
    if (!context || EVP_DigestInit_ex(context.get(), EVP_sha256(), nullptr) != 1)
      throw std::runtime_error("Cannot initialize content checksum");
  }
  void addData(QByteArrayView bytes) {
    if (!bytes.isEmpty() && EVP_DigestUpdate(context.get(), bytes.data(), size_t(bytes.size())) != 1)
      throw std::runtime_error("Cannot update content checksum");
  }
  bool addData(QIODevice *device) {
    std::array<char, 256 * 1024> buffer;
    while (!device->atEnd()) {
      const auto count = device->read(buffer.data(), buffer.size());
      if (count < 0 || (count == 0 && !device->atEnd())) return false;
      addData(QByteArrayView(buffer.data(), count));
    }
    return true;
  }
  QByteArray result() const {
    // snapshot the context so callers can inspect a digest and keep streaming
    std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> snapshot(EVP_MD_CTX_new(), EVP_MD_CTX_free);
    if (!snapshot || EVP_MD_CTX_copy_ex(snapshot.get(), context.get()) != 1)
      throw std::runtime_error("Cannot finalize content checksum");
    QByteArray bytes(EVP_MAX_MD_SIZE, Qt::Uninitialized);
    unsigned int count = 0;
    if (EVP_DigestFinal_ex(snapshot.get(), reinterpret_cast<unsigned char *>(bytes.data()), &count) != 1)
      throw std::runtime_error("Cannot finalize content checksum");
    bytes.resize(count);
    return bytes;
  }
private:
  std::unique_ptr<EVP_MD_CTX, decltype(&EVP_MD_CTX_free)> context;
};
