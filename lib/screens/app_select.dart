import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

import '../api.dart';
import '../models.dart';
import '../theme.dart';

class AppSelectPage extends StatefulWidget {
  final List<String> selected;
  final List<InstalledApp>? initialApps;
  const AppSelectPage({super.key, required this.selected, this.initialApps});

  @override
  State<AppSelectPage> createState() => _AppSelectPageState();
}

class _AppSelectPageState extends State<AppSelectPage> {
  late List<String> _selected;
  List<InstalledApp> _apps = const [];
  bool _loading = true;
  String _searchQuery = '';

  @override
  void initState() {
    super.initState();
    _selected = [...widget.selected];
    _loadApps();
  }

  Future<void> _loadApps() async {
    setState(() => _loading = true);
    try {
      _apps = widget.initialApps ?? await SmartAgentApi.getInstalledApps();
    } catch (_) {
      _apps = const [];
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  List<InstalledApp> get _filteredApps {
    if (_searchQuery.isEmpty) return _apps;
    final query = _searchQuery.toLowerCase();
    return _apps.where((app) {
      return app.name.toLowerCase().contains(query) ||
          app.package.toLowerCase().contains(query);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.white,
        elevation: 1,
        title: Text('选择应用', style: GoogleFonts.inter(fontWeight: FontWeight.w700, color: AppTheme.textPrimary)),
        iconTheme: const IconThemeData(color: AppTheme.textPrimary),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(_selected),
            child: Text('保存', style: GoogleFonts.inter(fontWeight: FontWeight.w700, color: AppTheme.vultrBlue)),
          ),
        ],
      ),
      backgroundColor: AppTheme.background,
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                // Search bar
                Container(
                  color: Colors.white,
                  padding: const EdgeInsets.all(12),
                  child: Container(
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: TextField(
                      onChanged: (value) => setState(() => _searchQuery = value),
                      decoration: InputDecoration(
                        hintText: '搜索应用名称或包名',
                        hintStyle: GoogleFonts.inter(color: AppTheme.textSecondary),
                        prefixIcon: const Icon(Icons.search, color: AppTheme.textSecondary),
                        suffixIcon: _searchQuery.isNotEmpty
                            ? IconButton(
                                icon: const Icon(Icons.clear, color: AppTheme.textSecondary),
                                onPressed: () => setState(() => _searchQuery = ''),
                              )
                            : null,
                        filled: true,
                        fillColor: AppTheme.background,
                        border: InputBorder.none,
                        focusedBorder: InputBorder.none,
                        enabledBorder: InputBorder.none,
                        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                      ),
                    ),
                  ),
                ),
                // App list
                Expanded(
                  child: _filteredApps.isEmpty
                      ? Center(
                          child: Text(
                            _searchQuery.isEmpty ? '未找到应用' : '未找到匹配的应用',
                            style: GoogleFonts.inter(color: AppTheme.textSecondary),
                          ),
                        )
                      : ListView.separated(
                          padding: const EdgeInsets.all(12),
                          itemCount: _filteredApps.length,
                          separatorBuilder: (_, __) => const SizedBox(height: 8),
                          itemBuilder: (context, index) {
                            final app = _filteredApps[index];
                            final checked = _selected.contains(app.package);
                            return Container(
                              decoration: BoxDecoration(
                                color: Colors.white,
                                borderRadius: BorderRadius.circular(12),
                                boxShadow: AppTheme.cardShadow,
                              ),
                              child: CheckboxListTile(
                                value: checked,
                                controlAffinity: ListTileControlAffinity.leading,
                                onChanged: (val) {
                                  setState(() {
                                    if (val == true) {
                                      _selected.add(app.package);
                                    } else {
                                      _selected.remove(app.package);
                                    }
                                  });
                                },
                                secondary: _buildIcon(app.iconBytes),
                                title: Text(
                                  app.name,
                                  style: GoogleFonts.inter(fontWeight: FontWeight.w700),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                subtitle: Text(
                                  app.package,
                                  style: GoogleFonts.inter(color: AppTheme.textSecondary),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            );
                          },
                        ),
                ),
              ],
            ),
    );
  }

  Widget _buildIcon(Uint8List? bytes) {
    if (bytes == null) {
      return Container(
        width: 40,
        height: 40,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: AppTheme.background,
          borderRadius: BorderRadius.circular(8),
        ),
        child: const Icon(Icons.apps, color: AppTheme.textSecondary),
      );
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: Image.memory(bytes, width: 40, height: 40, fit: BoxFit.cover),
    );
  }
}
