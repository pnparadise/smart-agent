import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../api.dart';
import '../models.dart';
import '../theme.dart';

class LogPage extends StatelessWidget {
  const LogPage({super.key});

  @override
  Widget build(BuildContext context) {
    return _LogPageBody(key: UniqueKey());
  }
}

class _LogPageBody extends StatefulWidget {
  const _LogPageBody({super.key});

  @override
  State<_LogPageBody> createState() => _LogPageBodyState();
}

class _LogPageBodyState extends State<_LogPageBody> {
  List<LogEntry> _logs = [];
  bool _loading = true;
  int _offset = 0;
  bool _hasMore = true;
  final ScrollController _scrollController = ScrollController();
  bool _isScrollable = false;
  bool _isLoadingMore = false;

  @override
  void initState() {
    super.initState();
    _loadInitial();
    _scrollController.addListener(_recalculateScrollable);
  }

  @override
  void dispose() {
    _scrollController.removeListener(_recalculateScrollable);
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _loadInitial() async {
    setState(() {
      _loading = true;
      _offset = 0;
      _hasMore = true;
    });
    final data = await SmartAgentApi.getLogs(limit: 10, offset: 0);
    if (!mounted) return;
    setState(() {
      _logs = data;
      _loading = false;
      _hasMore = data.length == 10;
    });
  }

  Future<void> _handleRefresh() async {
    await _loadInitial();
  }

  String _formatTime(DateTime dt) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${dt.year}-${two(dt.month)}-${two(dt.day)} ${two(dt.hour)}:${two(dt.minute)}:${two(dt.second)}';
  }

  void _recalculateScrollable() {
    if (!_scrollController.hasClients) return;
    final canScroll = _scrollController.position.maxScrollExtent > 0;
    if (canScroll != _isScrollable) {
      setState(() => _isScrollable = canScroll);
    }
    if (_hasMore &&
        !_isLoadingMore &&
        _scrollController.position.pixels >= _scrollController.position.maxScrollExtent - 100) {
      _loadMore();
    }
  }

  Future<void> _loadMore() async {
    if (_isLoadingMore || !_hasMore) return;
    setState(() => _isLoadingMore = true);
    final nextOffset = _offset + 10;
    final more = await SmartAgentApi.getLogs(limit: 10, offset: nextOffset);
    if (!mounted) return;
    setState(() {
      _isLoadingMore = false;
      if (more.isEmpty) {
        _hasMore = false;
      } else {
        _offset = nextOffset;
        _logs = [..._logs, ...more];
        _hasMore = more.length == 10;
      }
    });
  }

  Widget _buildLogItem(LogEntry log) {
    String type = "";
    String content = log.message;
    String? ssid;
    String? error;
    String? descPrefix;
    
    try {
      if (log.message.startsWith('{')) {
        final map = jsonDecode(log.message);
        if (map is Map) {
          type = map['type']?.toString() ?? "";
          final from = map['from']?.toString() ?? "";
          final to = map['to']?.toString() ?? "";
          ssid = map['ssid']?.toString();
          error = map['error']?.toString();
          descPrefix = map['descPrefix']?.toString();
          
          final suffix = (from.isNotEmpty && to.isNotEmpty && from != to)
              ? "$from -> $to"
              : (from.isNotEmpty ? from : to);

          final parts = <String>[
            if (descPrefix != null) descPrefix!.trim(),
            suffix.trim(),
          ].where((e) => e.isNotEmpty).toList();

          content = parts.isNotEmpty ? parts.join(' ') : log.message;
        }
      }
    } catch (e) {
      // Not JSON or parse error
    }

    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Material(
        color: Colors.white,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 22),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    _formatTime(log.timestamp),
                    style: GoogleFonts.inter(color: AppTheme.textSecondary, fontSize: 12),
                  ),
                  Row(
                    children: [
                      if (type.isNotEmpty)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: AppTheme.vultrBlue.withOpacity(0.1),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            type,
                            style: GoogleFonts.inter(
                              color: AppTheme.vultrBlue,
                              fontSize: 12,
                              fontWeight: FontWeight.w600
                            ),
                          ),
                        ),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 10),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Text(
                      content,
                      style: GoogleFonts.inter(fontWeight: FontWeight.w600, color: AppTheme.textPrimary),
                    ),
                  ),
                  if (ssid != null) ...[
                    const SizedBox(width: 8),
                    Text(
                      "WiFi: $ssid",
                      style: GoogleFonts.inter(color: AppTheme.textSecondary, fontSize: 12),
                    ),
                  ],
                ],
              ),
              if (error != null) ...[
                const SizedBox(height: 10),
                Text(
                  "错误: $error",
                  style: GoogleFonts.inter(color: Colors.redAccent, fontSize: 12, fontWeight: FontWeight.w600),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (!_loading) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _recalculateScrollable();
      });
    }

    return Scaffold(
      appBar: null,
      backgroundColor: AppTheme.background,
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                Expanded(
                  child: RefreshIndicator(
                    onRefresh: _handleRefresh,
                    child: _logs.isEmpty
                        ? ListView(
                            controller: _scrollController,
                            physics: const AlwaysScrollableScrollPhysics(),
                            padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
                            children: [
                              SizedBox(
                                height: MediaQuery.of(context).size.height * 0.6,
                                child: Center(
                                  child: Text('暂无日志', style: GoogleFonts.inter(color: AppTheme.textSecondary)),
                                ),
                              ),
                            ],
                          )
                        : ListView.separated(
                            controller: _scrollController,
                            physics: const AlwaysScrollableScrollPhysics(),
                            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                            itemCount: _logs.length + ((_hasMore || _offset == 0) ? 0 : 1),
                            separatorBuilder: (_, __) => const SizedBox(height: 10),
                            itemBuilder: (context, index) {
                            final showEnd = !_hasMore && _offset > 0;
                            if (showEnd && index == _logs.length) {
                              return Padding(
                                padding: const EdgeInsets.symmetric(vertical: 12),
                                child: Center(
                                  child: Text('已经到底了', style: GoogleFonts.inter(color: AppTheme.textSecondary)),
                                ),
                              );
                            }
                              return _buildLogItem(_logs[index]);
                            },
                          ),
                  ),
                ),
                if (_logs.isNotEmpty)
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
                    color: AppTheme.background,
                    child: ElevatedButton(
                      onPressed: () async {
                        await SmartAgentApi.clearLogs();
                        _handleRefresh();
                      },
                      style: ElevatedButton.styleFrom(
                        backgroundColor: AppTheme.vultrBlue,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                      ),
                      child: Text('清空日志', style: GoogleFonts.inter(fontWeight: FontWeight.w700)),
                    ),
                  )
              ],
            ),
    );
  }
}
