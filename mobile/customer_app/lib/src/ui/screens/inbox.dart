import 'package:flutter/material.dart';

import '../../api/models.dart';
import '../../core/ids.dart';
import '../widgets.dart';

/// Secure messages with the bank and in-app notifications.
class InboxScreen extends StatefulWidget {
  const InboxScreen({super.key});

  @override
  State<InboxScreen> createState() => _InboxScreenState();
}

class _InboxScreenState extends State<InboxScreen> {
  Key _reload = UniqueKey();

  Future<void> _compose() async {
    final l = context.l10n;
    final api = context.services.api;
    final subject = TextEditingController();
    final body = TextEditingController();
    final form = GlobalKey<FormState>();
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(l.newMessage),
        content: Form(
          key: form,
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            TextFormField(controller: subject, decoration: InputDecoration(labelText: l.subject),
                validator: requiredValidator(context)),
            const SizedBox(height: 12),
            TextFormField(controller: body, minLines: 3, maxLines: 6,
                decoration: InputDecoration(labelText: l.message), validator: requiredValidator(context)),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: Text(l.cancel)),
          FilledButton(
              onPressed: () {
                if (form.currentState?.validate() ?? false) Navigator.pop(c, true);
              },
              child: Text(l.send)),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final t = await runAction(context, () => api.startThread(subject: subject.text.trim(), body: body.text.trim()));
    if (t != null) setState(() => _reload = UniqueKey());
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final api = context.services.api;
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: Text(l.inbox),
          bottom: TabBar(tabs: [Tab(text: l.messages), Tab(text: l.notifications)]),
        ),
        floatingActionButton: FloatingActionButton(onPressed: _compose, tooltip: l.newMessage, child: const Icon(Icons.edit)),
        body: TabBarView(children: [
          Loader<List<MessageThread>>(
            key: _reload,
            load: api.threads,
            isEmpty: (d) => d.isEmpty,
            builder: (context, threads, reload) => RefreshIndicator(
              onRefresh: reload,
              child: ListView(children: [
                for (final t in threads)
                  ListTile(
                    leading: Badge(isLabelVisible: t.unread > 0, label: Text('${t.unread}'), child: const Icon(Icons.forum_outlined)),
                    title: Text(t.subject, style: t.unread > 0 ? const TextStyle(fontWeight: FontWeight.bold) : null),
                    subtitle: Text(t.status.replaceAll('_', ' ').toLowerCase()),
                    trailing: t.lastMessageAt == null ? null : Text(shortDate(context, t.lastMessageAt!)),
                    onTap: () async {
                      await Navigator.push(context, MaterialPageRoute(builder: (_) => ThreadScreen(thread: t)));
                      reload();
                    },
                  ),
              ]),
            ),
          ),
          Loader<List<AppNotification>>(
            load: api.notifications,
            isEmpty: (d) => d.isEmpty,
            builder: (context, items, reload) => RefreshIndicator(
              onRefresh: reload,
              child: ListView(children: [
                for (final n in items)
                  ListTile(
                    leading: Icon(n.category == 'SECURITY' ? Icons.shield_outlined : Icons.notifications_none),
                    title: Text(n.subject ?? n.category.toLowerCase()),
                    subtitle: Text(n.body),
                    trailing: Text(shortDate(context, n.createdAt)),
                  ),
              ]),
            ),
          ),
        ]),
      ),
    );
  }
}

class ThreadScreen extends StatefulWidget {
  const ThreadScreen({super.key, required this.thread});
  final MessageThread thread;

  @override
  State<ThreadScreen> createState() => _ThreadScreenState();
}

class _ThreadScreenState extends State<ThreadScreen> {
  final _text = TextEditingController();
  Key _reload = UniqueKey();
  bool _sending = false;
  bool _marked = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_marked && widget.thread.unread > 0) {
      _marked = true;
      context.services.api.markThreadRead(widget.thread.id).ignore();
    }
  }

  Future<void> _send() async {
    final body = _text.text.trim();
    if (body.isEmpty) return;
    setState(() => _sending = true);
    final m = await runAction(
        context, () => context.services.api.sendMessage(widget.thread.id, body, clientMessageId: uuidV4()));
    if (!mounted) return;
    setState(() {
      _sending = false;
      if (m != null) {
        _text.clear();
        _reload = UniqueKey();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final closed = widget.thread.status == 'CLOSED';
    return Scaffold(
      appBar: AppBar(title: Text(widget.thread.subject)),
      body: Column(children: [
        Expanded(
          child: Loader<List<SecureMessage>>(
            key: _reload,
            load: () => context.services.api.messages(widget.thread.id),
            builder: (context, msgs, reload) => ListView(padding: const EdgeInsets.all(12), children: [
              for (final m in msgs)
                Align(
                  alignment: m.mine ? Alignment.centerRight : Alignment.centerLeft,
                  child: Container(
                    margin: const EdgeInsets.symmetric(vertical: 4),
                    padding: const EdgeInsets.all(12),
                    constraints: const BoxConstraints(maxWidth: 320),
                    decoration: BoxDecoration(
                      color: m.mine ? cs.primaryContainer : cs.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(m.body),
                  ),
                ),
            ]),
          ),
        ),
        if (!closed)
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(8),
              child: Row(children: [
                Expanded(
                  child: TextField(controller: _text, minLines: 1, maxLines: 4,
                      decoration: InputDecoration(hintText: context.l10n.message)),
                ),
                IconButton(
                    tooltip: context.l10n.send, onPressed: _sending ? null : _send, icon: const Icon(Icons.send)),
              ]),
            ),
          ),
      ]),
    );
  }
}
