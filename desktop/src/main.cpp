#include <QGuiApplication>
#include <QIcon>
#include <QQmlApplicationEngine>
#include <QWindow>

#include "storage/LoggingSystem.h"
#include "CloudCommandListener.h"

int main(int argc, char *argv[]) {
  qputenv("QT_QUICK_CONTROLS_STYLE", QByteArray("Material"));
  qputenv("QT_QUICK_CONTROLS_MATERIAL_THEME", QByteArray("Dark"));
  qputenv("QT_QUICK_CONTROLS_MATERIAL_ACCENT", QByteArray("#E08427"));
  qputenv("QT_QUICK_CONTROLS_MATERIAL_VARIANT", QByteArray("Dense"));
  qputenv("QT_QUICK_CONTROLS_MATERIAL_PRIMARY", QByteArray("Red"));
  qputenv("QT_QUICK_CONTROLS_MATERIAL_ACCENT", QByteArray("Teal"));
  LoggingSystem::Init("desktop");

  QGuiApplication app(argc, argv);
  app.setQuitOnLastWindowClosed(false);
  QGuiApplication::setWindowIcon(QIcon(":/res/icons/icon.png"));

  auto url = QUrl("qrc:/ui/MainWindow.qml");
  QQmlApplicationEngine engine{};
  QObject::connect(
      &engine, &QQmlApplicationEngine::objectCreated, &app,
      [url](QObject *obj, const QUrl &objUrl) {
        if(!obj && url == objUrl) {
          QCoreApplication::exit(-1);
        }
      },
      Qt::QueuedConnection);
  engine.load(url);

  const auto background = app.arguments().contains("--background");
  if(background && !engine.rootObjects().empty()) {
    if(auto *window = qobject_cast<QWindow *>(engine.rootObjects().first())) window->hide();
  }
  CloudCommandListener cloudCommands;

  auto result = QGuiApplication::exec();
  LoggingSystem::Destroy();
  return result;
}
