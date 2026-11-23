#pragma once
#include <QProcess>
#include <QTimer>
#include <QVariant>
#ifdef Q_OS_WIN
#include <windows.h>
#include <memory>
#else
#include <signal.h>
#include <unistd.h>
#endif

// keep cancellation and exit scoped to the tool process tree on each platform
inline void configureToolProcess(QProcess *p, bool lowerPriority = false) {
#ifdef Q_OS_WIN
  struct Job { HANDLE handle = nullptr; ~Job() { if (handle) CloseHandle(handle); } };
  auto job = std::make_shared<Job>();
  job->handle = CreateJobObjectW(nullptr, nullptr);
  if (job->handle) {
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    SetInformationJobObject(job->handle, JobObjectExtendedLimitInformation, &limits, sizeof(limits));
  }
  p->setCreateProcessArgumentsModifier([lowerPriority](QProcess::CreateProcessArguments *args) {
    args->flags |= CREATE_NO_WINDOW | (lowerPriority ? BELOW_NORMAL_PRIORITY_CLASS : 0);
  });
  QObject::connect(p, &QProcess::started, p, [p, job] {
    HANDLE process = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, FALSE, DWORD(p->processId()));
    if (process) {
      if (job->handle && AssignProcessToJobObject(job->handle, process))
        p->setProperty("sillyJob", qulonglong(reinterpret_cast<quintptr>(job->handle)));
      CloseHandle(process);
    }
  });
  QObject::connect(p, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), p, [p, job] {
    p->setProperty("sillyJob", QVariant());
    if (job->handle) { CloseHandle(job->handle); job->handle = nullptr; }
  });
#else
  p->setChildProcessModifier([lowerPriority] {
    ::setsid();
    if (lowerPriority) { const int result = ::nice(5); (void)result; }
  });
#endif
}
inline void terminateGroup(QProcess *p, bool force = false) {
  if (p->processId() <= 0) return;
#ifdef Q_OS_WIN
  const auto job = reinterpret_cast<HANDLE>(quintptr(p->property("sillyJob").toULongLong()));
  if (job) TerminateJobObject(job, 1);
  else p->kill();
  Q_UNUSED(force);
#else
  ::kill(-pid_t(p->processId()), force ? SIGKILL : SIGTERM);
#endif
}
inline void discardProcess(QProcess *p, QObject *receiver) {
  QObject::disconnect(p, nullptr, receiver, nullptr);
  if (p->state() == QProcess::NotRunning) {
    p->deleteLater();
    return;
  }
  QObject::connect(p, qOverload<int, QProcess::ExitStatus>(&QProcess::finished),
                   p, &QObject::deleteLater);
  QObject::connect(p, &QProcess::errorOccurred, p, [p](auto error) {
    if (error == QProcess::FailedToStart) p->deleteLater();
  });
  if (p->state() == QProcess::Starting)
    QObject::connect(p, &QProcess::started, p, [p] { terminateGroup(p, true); });
  else terminateGroup(p, true);
}
inline void deadline(QProcess *p, int ms) {
  QTimer::singleShot(ms, p, [p] {
    if (p->state() != QProcess::NotRunning) {
      p->setProperty("timedOut", true);
      terminateGroup(p, true);
    }
  });
}
