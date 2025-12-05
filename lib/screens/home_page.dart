import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'tunnel_list.dart';
import 'smart_rules.dart';
import 'log_page.dart';
import '../theme.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with SingleTickerProviderStateMixin {
  late TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Icon(Icons.vpn_key, color: AppTheme.vultrBlue),
            const SizedBox(width: 12),
            Text('SmartAgent', style: GoogleFonts.inter(fontWeight: FontWeight.w700)),
          ],
        ),
        elevation: 0, // Remove default shadow
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(48),
          child: Container(
            decoration: BoxDecoration(
              color: Colors.white,
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withOpacity(0.05),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                ),
              ],
            ),
            child: TabBar(
              controller: _tabController,
              labelColor: AppTheme.vultrBlue,
              unselectedLabelColor: AppTheme.textSecondary,
              indicatorColor: AppTheme.vultrBlue,
              indicatorSize: TabBarIndicatorSize.label,
              labelStyle: GoogleFonts.inter(fontWeight: FontWeight.w600),
              dividerColor: Colors.transparent, // Remove divider line
              tabs: const [
                Tab(text: 'Tunnels'),
                Tab(text: 'Rules'),
                Tab(text: 'Logs'),
              ],
            ),
          ),
        ),
      ),
      body: TabBarView(
        controller: _tabController,
        children: const [
          TunnelListScreen(),
          SmartRulesScreen(),
          LogPage(),
        ],
      ),
    );
  }
}