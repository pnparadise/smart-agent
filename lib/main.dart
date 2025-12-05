import 'package:flutter/material.dart';
import 'theme.dart';
import 'screens/home_page.dart';
import 'services/message_channel.dart';
import 'widgets/notifier.dart';
import 'dart:async';

void main() {
  runApp(const SmartAgentApp());
}

class SmartAgentApp extends StatefulWidget {
  const SmartAgentApp({super.key});

  @override
  State<SmartAgentApp> createState() => _SmartAgentAppState();
}

class _SmartAgentAppState extends State<SmartAgentApp> {
  StreamSubscription<String>? _messageSub;

  @override
  void initState() {
    super.initState();
    _messageSub = MessageChannel.messages.listen((msg) {
      if (msg.isNotEmpty) Notifier.showGlobal(msg);
    });
  }

  @override
  void dispose() {
    _messageSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SmartAgent',
      theme: AppTheme.theme,
      scaffoldMessengerKey: Notifier.messengerKey,
      home: const HomePage(),
      debugShowCheckedModeBanner: false,
    );
  }
}
