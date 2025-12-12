import 'package:flutter/material.dart';
import 'theme.dart';
import 'screens/home_page.dart';

void main() {
  runApp(const SmartAgentApp());
}

class SmartAgentApp extends StatefulWidget {
  const SmartAgentApp({super.key});

  @override
  State<SmartAgentApp> createState() => _SmartAgentAppState();
}

class _SmartAgentAppState extends State<SmartAgentApp> {

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SmartAgent',
      theme: AppTheme.theme,
      home: const HomePage(),
      debugShowCheckedModeBanner: false,
    );
  }
}
