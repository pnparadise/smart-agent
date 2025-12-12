import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import '../api.dart';
import '../models.dart';
import '../theme.dart';
import '../widgets/toast.dart';

class DebugLogPage extends StatefulWidget {
  const DebugLogPage({super.key});

  @override
  State<DebugLogPage> createState() => _DebugLogPageState();
}

class _DebugLogPageState extends State<DebugLogPage> {
  List<LogEntry> _logs = [];
  bool _loading = true;
  bool _hasMore = true;
  int _offset = 0;
  bool _isLoadingMore = false;
  final ScrollController _controller = ScrollController();

  @override
  void initState() {
    super.initState();
    _loadInitial();
    _controller.addListener(_maybeLoadMore);
  }

  @override
  void dispose() {
    _controller.removeListener(_maybeLoadMore);
    _controller.dispose();
    super.dispose();
  }

  Future<void> _loadInitial() async {
    setState(() {
      _loading = true;
      _offset = 0;
      _hasMore = true;
    });
    final data = await SmartAgentApi.getDebugLogs(limit: 20, offset: 0);
    if (!mounted) return;
    setState(() {
      _logs = data;
      _loading = false;
      _hasMore = data.length == 20;
    });
  }

  Future<void> _loadMore() async {
    if (_isLoadingMore || !_hasMore) return;
    setState(() => _isLoadingMore = true);
    final nextOffset = _offset + 20;
    final more = await SmartAgentApi.getDebugLogs(limit: 20, offset: nextOffset);
    if (!mounted) return;
    setState(() {
      _offset = nextOffset;
      _isLoadingMore = false;
      if (more.isEmpty) {
        _hasMore = false;
      } else {
        _logs = [..._logs, ...more];
        _hasMore = more.length == 20;
      }
    });
  }

  void _maybeLoadMore() {
    if (!_controller.hasClients) return;
    if (_hasMore &&
        !_isLoadingMore &&
        _controller.position.pixels >= _controller.position.maxScrollExtent - 120) {
      _loadMore();
    }
  }

  String _formatTime(DateTime dt) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${dt.year}-${two(dt.month)}-${two(dt.day)} ${two(dt.hour)}:${two(dt.minute)}:${two(dt.second)}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('调试日志'),
        actions: [
          IconButton(
            icon: const Icon(Icons.copy_rounded),
            onPressed: _copyLogsToClipboard,
            tooltip: '复制全部',
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _loadInitial,
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView.separated(
                controller: _controller,
                itemBuilder: (context, index) {
                  if (index >= _logs.length) {
                    return _hasMore
                        ? const Padding(
                            padding: EdgeInsets.symmetric(vertical: 16),
                            child: Center(child: CircularProgressIndicator()),
                          )
                        : const SizedBox.shrink();
                  }
                  final log = _logs[index];
                  final parsed = _parseMessage(log.message);
                  return ListTile(
                    title: Text(
                      parsed['message'] ?? log.message,
                      style: GoogleFonts.inter(fontWeight: FontWeight.w600),
                    ),
                    subtitle: Text(
                      '${_formatTime(log.timestamp)}${parsed['tag'] != null ? ' · ${parsed['tag']}' : ''}',
                      style: GoogleFonts.inter(color: AppTheme.textSecondary, fontSize: 12),
                    ),
                  );
                },
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemCount: _logs.length + 1,
              ),
      ),
    );
  }

  Future<void> _copyLogsToClipboard() async {
    if (_logs.isEmpty) {
      if (mounted) {
        Toast.showInfo(context, '暂无日志可复制');
      }
      return;
    }
    final buffer = StringBuffer();
    for (final log in _logs) {
      final parsed = _parseMessage(log.message);
      final tagPart = parsed['tag'] != null ? ' ${parsed['tag']}' : '';
      buffer.writeln('[${_formatTime(log.timestamp)}$tagPart] ${parsed['message'] ?? log.message}');
    }
    await Clipboard.setData(ClipboardData(text: buffer.toString()));
    if (!mounted) return;
    Toast.showSuccess(context, '调试日志已复制');
  }

  Map<String, String?> _parseMessage(String raw) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is Map) {
        return {
          'message': decoded['message']?.toString(),
          'tag': decoded['tag']?.toString(),
        };
      }
    } catch (_) {}
    return {'message': raw};
  }
}
