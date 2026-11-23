#pragma once
#include <QFile>
#include <QFileInfo>
#include <QString>
#include <taglib/fileref.h>
#ifdef Q_OS_WIN
#include <windows.h>
#else
#include <sys/stat.h>
#include <cerrno>
#include <cstring>
#endif

inline QString fileIdentity(const QString &path) {
#ifdef Q_OS_WIN
  HANDLE handle = CreateFileW(reinterpret_cast<LPCWSTR>(path.utf16()), FILE_READ_ATTRIBUTES,
      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0, nullptr);
  if (handle == INVALID_HANDLE_VALUE) return {};
  BY_HANDLE_FILE_INFORMATION info{};
  const bool ok = GetFileInformationByHandle(handle, &info);
  CloseHandle(handle);
  if (!ok) return {};
  return QString::number(info.dwVolumeSerialNumber) + ":" +
      QString::number((quint64(info.nFileIndexHigh) << 32) | info.nFileIndexLow);
#else
  struct stat info{};
  if (::stat(QFile::encodeName(path).constData(), &info)) return {};
  return QString::number(info.st_dev) + ":" + QString::number(info.st_ino);
#endif
}
inline TagLib::FileRef taggedFile(const QString &path) {
#ifdef Q_OS_WIN
  return TagLib::FileRef(reinterpret_cast<const wchar_t *>(path.utf16()));
#else
  return TagLib::FileRef(QFile::encodeName(path).constData());
#endif
}
inline QString replaceAudioFile(const QString &source, const QString &target) {
#ifdef Q_OS_WIN
  if (MoveFileExW(reinterpret_cast<LPCWSTR>(source.utf16()), reinterpret_cast<LPCWSTR>(target.utf16()),
                  MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) return {};
  return "Windows file replacement error " + QString::number(GetLastError());
#else
  if (::rename(QFile::encodeName(source).constData(), QFile::encodeName(target).constData()) == 0) return {};
  return QString::fromLocal8Bit(std::strerror(errno));
#endif
}
