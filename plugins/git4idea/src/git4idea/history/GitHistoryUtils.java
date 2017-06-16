/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.history;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.diff.ItemLatestState;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsRevisionDescription;
import com.intellij.openapi.vcs.history.VcsRevisionDescriptionImpl;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.VcsCommitMetadata;
import com.intellij.vcs.log.VcsLogObjectsFactory;
import git4idea.*;
import git4idea.branch.GitBranchUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitLineHandler;
import git4idea.commands.GitLineHandlerAdapter;
import git4idea.commands.GitSimpleHandler;
import git4idea.config.GitVersion;
import git4idea.config.GitVersionSpecialty;
import git4idea.history.browser.SHAHash;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.intellij.util.ObjectUtils.notNull;
import static git4idea.history.GitLogParser.GitLogOption.*;

/**
 * A collection of methods for retrieving history information from native Git.
 */
public class GitHistoryUtils {

  private static final Logger LOG = Logger.getInstance("#git4idea.history.GitHistoryUtils");

  private GitHistoryUtils() {
  }

  /**
   * Get current revision for the file under git in the current or specified branch.
   *
   * @param project  a project
   * @param filePath file path to the file which revision is to be retrieved.
   * @param branch   name of branch or null if current branch wanted.
   * @return revision number or null if the file is unversioned or new.
   * @throws VcsException if there is a problem with running git.
   */
  @Nullable
  public static VcsRevisionNumber getCurrentRevision(@NotNull Project project, @NotNull FilePath filePath,
                                                     @Nullable String branch) throws VcsException {
    filePath = getLastCommitName(project, filePath);
    GitSimpleHandler h = new GitSimpleHandler(project, GitUtil.getGitRoot(filePath), GitCommand.LOG);
    GitLogParser parser = new GitLogParser(project, HASH, COMMIT_TIME);
    h.setSilent(true);
    h.addParameters("-n1", parser.getPretty());
    h.addParameters(!StringUtil.isEmpty(branch) ? branch : "--all");
    h.endOptions();
    h.addRelativePaths(filePath);
    String result = h.run();
    if (result.length() == 0) {
      return null;
    }
    final GitLogRecord record = parser.parseOneRecord(result);
    if (record == null) {
      return null;
    }
    record.setUsedHandler(h);
    return new GitRevisionNumber(record.getHash(), record.getDate());
  }

  @Nullable
  public static VcsRevisionDescription getCurrentRevisionDescription(@NotNull Project project, @NotNull FilePath filePath)
    throws VcsException {
    filePath = getLastCommitName(project, filePath);
    GitSimpleHandler h = new GitSimpleHandler(project, GitUtil.getGitRoot(filePath), GitCommand.LOG);
    GitLogParser parser = new GitLogParser(project, HASH, COMMIT_TIME, AUTHOR_NAME, COMMITTER_NAME, SUBJECT, BODY, RAW_BODY);
    h.setSilent(true);
    h.addParameters("-n1", parser.getPretty());
    h.addParameters("--all");
    h.endOptions();
    h.addRelativePaths(filePath);
    String result = h.run();
    if (result.length() == 0) {
      return null;
    }
    final GitLogRecord record = parser.parseOneRecord(result);
    if (record == null) {
      return null;
    }
    record.setUsedHandler(h);

    final String author = Comparing.equal(record.getAuthorName(), record.getCommitterName()) ? record.getAuthorName() :
                          record.getAuthorName() + " (" + record.getCommitterName() + ")";
    return new VcsRevisionDescriptionImpl(new GitRevisionNumber(record.getHash(), record.getDate()), record.getDate(), author,
                                          record.getFullMessage());
  }

  /**
   * Get current revision for the file under git
   *
   * @param project  a project
   * @param filePath a file path
   * @return a revision number or null if the file is unversioned or new
   * @throws VcsException if there is problem with running git
   */
  @Nullable
  public static ItemLatestState getLastRevision(@NotNull Project project, @NotNull FilePath filePath) throws VcsException {
    VirtualFile root = GitUtil.getGitRoot(filePath);
    GitBranch c = GitBranchUtil.getCurrentBranch(project, root);
    GitBranch t = c == null ? null : GitBranchUtil.tracked(project, root, c.getName());
    if (t == null) {
      return new ItemLatestState(getCurrentRevision(project, filePath, null), true, false);
    }
    filePath = getLastCommitName(project, filePath);
    GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.LOG);
    GitLogParser parser = new GitLogParser(project, GitLogParser.NameStatus.STATUS, HASH, COMMIT_TIME, PARENTS);
    h.setSilent(true);
    h.addParameters("-n1", parser.getPretty(), "--name-status", t.getFullName());
    h.endOptions();
    h.addRelativePaths(filePath);
    String result = h.run();
    if (result.length() == 0) {
      return null;
    }
    GitLogRecord record = parser.parseOneRecord(result);
    if (record == null) {
      return null;
    }
    final List<Change> changes = record.parseChanges(project, root);
    boolean exists = changes.isEmpty() || !FileStatus.DELETED.equals(changes.get(0).getFileStatus());
    record.setUsedHandler(h);
    return new ItemLatestState(new GitRevisionNumber(record.getHash(), record.getDate()), exists, false);
  }

  /*
   === Smart full log with renames ===
   'git log --follow' does detect renames, but it has a bug - merge commits aren't handled properly: they just dissapear from the history.
   See http://kerneltrap.org/mailarchive/git/2009/1/30/4861054 and the whole thread about that: --follow is buggy, but maybe it won't be fixed.
   To get the whole history through renames we do the following:
   1. 'git log <file>' - and we get the history since the first rename, if there was one.
   2. 'git show -M --follow --name-status <first_commit_id> -- <file>'
      where <first_commit_id> is the hash of the first commit in the history we got in #1.
      With this command we get the rename-detection-friendly information about the first commit of the given file history.
      (by specifying the <file> we filter out other changes in that commit; but in that case rename detection requires '--follow' to work,
      that's safe for one commit though)
      If the first commit was ADDING the file, then there were no renames with this file, we have the full history.
      But if the first commit was RENAMING the file, we are going to query for the history before rename.
      Now we have the previous name of the file:

        ~/sandbox/git # git show --oneline --name-status -M 4185b97
        4185b97 renamed a to b
        R100    a       b

   3. 'git log <rename_commit_id> -- <previous_file_name>' - get the history of a before the given commit.
      We need to specify <rename_commit_id> here, because <previous_file_name> could have some new history, which has nothing common with our <file>.
      Then we repeat 2 and 3 until the first commit is ADDING the file, not RENAMING it.

    TODO: handle multiple repositories configuration: a file can be moved from one repo to another
   */

  /**
   * Retrieves the history of the file, including renames.
   *
   * @param project
   * @param path              FilePath which history is queried.
   * @param root              Git root - optional: if this is null, then git root will be detected automatically.
   * @param consumer          This consumer is notified ({@link Consumer#consume(Object)} when new history records are retrieved.
   * @param exceptionConsumer This consumer is notified in case of error while executing git command.
   * @param parameters        Optional parameters which will be added to the git log command just before the path.
   */
  public static void history(@NotNull Project project,
                             @NotNull FilePath path,
                             @Nullable VirtualFile root,
                             @NotNull Consumer<GitFileRevision> consumer,
                             @NotNull Consumer<VcsException> exceptionConsumer,
                             String... parameters) {
    history(project, path, root, GitRevisionNumber.HEAD, consumer, exceptionConsumer, parameters);
  }

  public static void history(@NotNull Project project,
                             @NotNull FilePath path,
                             @Nullable VirtualFile root,
                             @NotNull VcsRevisionNumber startingRevision,
                             @NotNull Consumer<GitFileRevision> consumer,
                             @NotNull Consumer<VcsException> exceptionConsumer,
                             String... parameters) {
    // adjust path using change manager
    final FilePath filePath = getLastCommitName(project, path);
    final VirtualFile finalRoot;
    try {
      finalRoot = (root == null ? GitUtil.getGitRoot(filePath) : root);
    }
    catch (VcsException e) {
      exceptionConsumer.consume(e);
      return;
    }
    final GitLogParser logParser = new GitLogParser(project, GitLogParser.NameStatus.STATUS,
                                                    HASH, COMMIT_TIME, AUTHOR_NAME, AUTHOR_EMAIL, COMMITTER_NAME, COMMITTER_EMAIL, PARENTS,
                                                    SUBJECT, BODY, RAW_BODY, AUTHOR_TIME);

    final AtomicReference<String> firstCommit = new AtomicReference<>(startingRevision.asString());
    final AtomicReference<String> firstCommitParent = new AtomicReference<>(firstCommit.get());
    final AtomicReference<FilePath> currentPath = new AtomicReference<>(filePath);
    final AtomicReference<GitLineHandler> logHandler = new AtomicReference<>();
    final AtomicBoolean skipFurtherOutput = new AtomicBoolean();

    final Consumer<GitLogRecord> resultAdapter = record -> {
      if (skipFurtherOutput.get()) {
        return;
      }
      if (record == null) {
        exceptionConsumer.consume(new VcsException("revision details are null."));
        return;
      }
      record.setUsedHandler(logHandler.get());
      final GitRevisionNumber revision = new GitRevisionNumber(record.getHash(), record.getDate());
      firstCommit.set(record.getHash());
      final String[] parentHashes = record.getParentsHashes();
      if (parentHashes.length < 1) {
        firstCommitParent.set(null);
      }
      else {
        firstCommitParent.set(parentHashes[0]);
      }
      final String message = record.getFullMessage();

      FilePath revisionPath;
      try {
        final List<FilePath> paths = record.getFilePaths(finalRoot);
        if (paths.size() > 0) {
          revisionPath = paths.get(0);
        }
        else {
          // no paths are shown for merge commits, so we're using the saved path we're inspecting now
          revisionPath = currentPath.get();
        }

        Couple<String> authorPair = Couple.of(record.getAuthorName(), record.getAuthorEmail());
        Couple<String> committerPair = Couple.of(record.getCommitterName(), record.getCommitterEmail());
        Collection<String> parents = Arrays.asList(parentHashes);
        consumer.consume(new GitFileRevision(project, finalRoot, revisionPath, revision, Couple.of(authorPair, committerPair), message,
                                             null, new Date(record.getAuthorTimeStamp()), parents));
        List<GitLogStatusInfo> statusInfos = record.getStatusInfos();
        if (statusInfos.isEmpty()) {
          // can safely be empty, for example, for simple merge commits that don't change anything.
          return;
        }
        if (statusInfos.get(0).getType() == GitChangeType.ADDED && !filePath.isDirectory()) {
          skipFurtherOutput.set(true);
        }
      }
      catch (VcsException e) {
        exceptionConsumer.consume(e);
      }
    };

    GitVcs vcs = GitVcs.getInstance(project);
    GitVersion version = vcs != null ? vcs.getVersion() : GitVersion.NULL;
    final AtomicBoolean criticalFailure = new AtomicBoolean();
    while (currentPath.get() != null && firstCommitParent.get() != null) {
      logHandler.set(getLogHandler(project, version, finalRoot, logParser, currentPath.get(), firstCommitParent.get(), parameters));
      final MyTokenAccumulator accumulator = new MyTokenAccumulator(logParser);
      final Semaphore semaphore = new Semaphore();

      logHandler.get().addLineListener(new GitLineHandlerAdapter() {
        @Override
        public void onLineAvailable(String line, Key outputType) {
          final GitLogRecord record = accumulator.acceptLine(line);
          if (record != null) {
            resultAdapter.consume(record);
          }
        }

        @Override
        public void startFailed(Throwable exception) {
          //noinspection ThrowableInstanceNeverThrown
          try {
            exceptionConsumer.consume(new VcsException(exception));
          }
          finally {
            criticalFailure.set(true);
            semaphore.up();
          }
        }

        @Override
        public void processTerminated(int exitCode) {
          try {
            super.processTerminated(exitCode);
            final GitLogRecord record = accumulator.processLast();
            if (record != null) {
              resultAdapter.consume(record);
            }
          }
          catch (ProcessCanceledException ignored) {
          }
          catch (Throwable t) {
            LOG.error(t);
            exceptionConsumer.consume(new VcsException("Internal error " + t.getMessage(), t));
            criticalFailure.set(true);
          }
          finally {
            semaphore.up();
          }
        }
      });
      semaphore.down();
      logHandler.get().start();
      semaphore.waitFor();
      if (criticalFailure.get()) {
        return;
      }

      try {
        Pair<String, FilePath> firstCommitParentAndPath = getFirstCommitParentAndPathIfRename(project, finalRoot, firstCommit.get(),
                                                                                              currentPath.get(), version);
        currentPath.set(firstCommitParentAndPath == null ? null : firstCommitParentAndPath.second);
        firstCommitParent.set(firstCommitParentAndPath == null ? null : firstCommitParentAndPath.first);
        skipFurtherOutput.set(false);
      }
      catch (VcsException e) {
        LOG.warn("Tried to get first commit rename path", e);
        exceptionConsumer.consume(e);
        return;
      }
    }
  }

  @NotNull
  private static GitLineHandler getLogHandler(@NotNull Project project,
                                              @NotNull GitVersion version,
                                              @NotNull VirtualFile root,
                                              @NotNull GitLogParser parser,
                                              @NotNull FilePath path,
                                              @NotNull String lastCommit,
                                              String... parameters) {
    final GitLineHandler h = new GitLineHandler(project, root, GitCommand.LOG);
    h.setStdoutSuppressed(true);
    h.addParameters("--name-status", parser.getPretty(), "--encoding=UTF-8", lastCommit);
    if (GitVersionSpecialty.FULL_HISTORY_SIMPLIFY_MERGES_WORKS_CORRECTLY.existsIn(version) && Registry.is("git.file.history.full")) {
      h.addParameters("--full-history", "--simplify-merges");
    }
    if (parameters != null && parameters.length > 0) {
      h.addParameters(parameters);
    }
    h.endOptions();
    h.addRelativePaths(path);
    return h;
  }

  /**
   * Gets info of the given commit and checks if it was a RENAME.
   * If yes, returns the older file path, which file was renamed from.
   * If it's not a rename, returns null.
   */
  @Nullable
  private static Pair<String, FilePath> getFirstCommitParentAndPathIfRename(@NotNull Project project,
                                                                            @NotNull VirtualFile root,
                                                                            @NotNull String commit,
                                                                            @NotNull FilePath filePath,
                                                                            @NotNull GitVersion version) throws VcsException {
    // 'git show -M --name-status <commit hash>' returns the information about commit and detects renames.
    // NB: we can't specify the filepath, because then rename detection will work only with the '--follow' option, which we don't wanna use.
    final GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.SHOW);
    final GitLogParser parser = new GitLogParser(project, GitLogParser.NameStatus.STATUS, HASH, COMMIT_TIME, PARENTS);
    h.setStdoutSuppressed(true);
    h.addParameters("-M", "--name-status", parser.getPretty(), "--encoding=UTF-8", commit);
    if (!GitVersionSpecialty.FOLLOW_IS_BUGGY_IN_THE_LOG.existsIn(version)) {
      h.addParameters("--follow");
      h.endOptions();
      h.addRelativePaths(filePath);
    }
    else {
      h.endOptions();
    }
    final String output = h.run();
    final List<GitLogRecord> records = parser.parse(output);

    if (records.isEmpty()) return null;
    // we have information about all changed files of the commit. Extracting information about the file we need.
    GitLogRecord record = records.get(0);
    final List<Change> changes = record.parseChanges(project, root);
    for (Change change : changes) {
      if ((change.isMoved() || change.isRenamed()) && filePath.equals(notNull(change.getAfterRevision()).getFile())) {
        final String[] parents = record.getParentsHashes();
        String parent = parents.length > 0 ? parents[0] : null;
        return Pair.create(parent, notNull(change.getBeforeRevision()).getFile());
      }
    }
    return null;
  }

  @Nullable
  public static List<VcsCommitMetadata> readLastCommits(@NotNull Project project,
                                                        @NotNull VirtualFile root,
                                                        @NotNull String... refs)
    throws VcsException {
    final VcsLogObjectsFactory factory = GitLogUtil.getObjectsFactoryWithDisposeCheck(project);
    if (factory == null) {
      return null;
    }

    GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.LOG);
    GitLogParser parser = new GitLogParser(project, GitLogParser.NameStatus.NONE, HASH, PARENTS, COMMIT_TIME, SUBJECT, AUTHOR_NAME,
                                           AUTHOR_EMAIL, RAW_BODY, COMMITTER_NAME, COMMITTER_EMAIL, AUTHOR_TIME);

    h.setSilent(true);
    // git show can show either -p, or --name-status, or --name-only, but we need nothing, just details => using git log --no-walk
    h.addParameters("--no-walk");
    h.addParameters(parser.getPretty(), "--encoding=UTF-8");
    h.addParameters(refs);
    h.endOptions();

    String output = h.run();
    List<GitLogRecord> records = parser.parse(output);
    if (records.size() != refs.length) return null;

    return ContainerUtil.map(records,
                             record -> factory.createCommitMetadata(factory.createHash(record.getHash()),
                                                                    GitLogUtil.getParentHashes(factory, record),
                                                                    record.getCommitTime(),
                                                                    root, record.getSubject(), record.getAuthorName(),
                                                                    record.getAuthorEmail(),
                                                                    record.getFullMessage(), record.getCommitterName(),
                                                                    record.getCommitterEmail(),
                                                                    record.getAuthorTimeStamp()));
  }

  private static class MyTokenAccumulator {
    @NotNull private final StringBuilder myBuffer = new StringBuilder();
    @NotNull private final GitLogParser myParser;

    private boolean myNotStarted = true;

    public MyTokenAccumulator(@NotNull GitLogParser parser) {
      myParser = parser;
    }

    @Nullable
    public GitLogRecord acceptLine(String s) {
      final boolean recordStart = s.startsWith(GitLogParser.RECORD_START);
      if (recordStart) {
        s = s.substring(GitLogParser.RECORD_START.length());
      }

      if (myNotStarted) {
        myBuffer.append(s);
        myBuffer.append("\n");

        myNotStarted = false;
        return null;
      }
      else if (recordStart) {
        final String line = myBuffer.toString();
        myBuffer.setLength(0);

        myBuffer.append(s);
        myBuffer.append("\n");

        return processResult(line);
      }
      else {
        myBuffer.append(s);
        myBuffer.append("\n");
        return null;
      }
    }

    @Nullable
    public GitLogRecord processLast() {
      return processResult(myBuffer.toString());
    }

    @Nullable
    private GitLogRecord processResult(@NotNull String line) {
      return myParser.parseOneRecord(line);
    }
  }

  /**
   * Get history for the file
   *
   * @param project the context project
   * @param path    the file path
   * @return the list of the revisions
   * @throws VcsException if there is problem with running git
   */
  @NotNull
  public static List<VcsFileRevision> history(@NotNull Project project, @NotNull FilePath path, String... parameters) throws VcsException {
    VirtualFile root = ProjectLevelVcsManager.getInstance(project).getVcsRootFor(path);
    if (root == null) {
      throw new VcsException("The file " + path + " is not under vcs.");
    }
    return history(project, path, root, parameters);
  }

  @NotNull
  public static List<VcsFileRevision> history(@NotNull Project project,
                                              @NotNull FilePath path,
                                              @Nullable VirtualFile root,
                                              String... parameters) throws VcsException {
    return history(project, path, root, GitRevisionNumber.HEAD, parameters);
  }

  @NotNull
  public static List<VcsFileRevision> history(@NotNull Project project,
                                              @NotNull FilePath path,
                                              @Nullable VirtualFile root,
                                              @NotNull VcsRevisionNumber startingFrom,
                                              String... parameters) throws VcsException {
    final List<VcsFileRevision> rc = new ArrayList<>();
    final List<VcsException> exceptions = new ArrayList<>();

    history(project, path, root, startingFrom, rc::add, exceptions::add, parameters);
    if (!exceptions.isEmpty()) {
      throw exceptions.get(0);
    }
    return rc;
  }

  /**
   * @deprecated To remove in IDEA 17
   */
  @Deprecated
  @SuppressWarnings("unused")
  @NotNull
  public static List<Pair<SHAHash, Date>> onlyHashesHistory(@NotNull Project project, @NotNull FilePath path, String... parameters)
    throws VcsException {
    final VirtualFile root = GitUtil.getGitRoot(path);
    return onlyHashesHistory(project, path, root, parameters);
  }

  /**
   * @deprecated To remove in IDEA 17
   */
  @Deprecated
  @NotNull
  public static List<Pair<SHAHash, Date>> onlyHashesHistory(@NotNull Project project,
                                                            @NotNull FilePath path,
                                                            @NotNull VirtualFile root,
                                                            String... parameters)
    throws VcsException {
    // adjust path using change manager
    path = getLastCommitName(project, path);
    GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.LOG);
    GitLogParser parser = new GitLogParser(project, HASH, COMMIT_TIME);
    h.setStdoutSuppressed(true);
    h.addParameters(parameters);
    h.addParameters(parser.getPretty(), "--encoding=UTF-8");
    h.endOptions();
    h.addRelativePaths(path);
    String output = h.run();

    final List<Pair<SHAHash, Date>> rc = new ArrayList<>();
    for (GitLogRecord record : parser.parse(output)) {
      record.setUsedHandler(h);
      rc.add(Pair.create(new SHAHash(record.getHash()), record.getDate()));
    }
    return rc;
  }

  /**
   * <p>Get & parse git log detailed output with commits, their parents and their changes.</p>
   * <p>
   * <p>Warning: this is method is efficient by speed, but don't query too much, because the whole log output is retrieved at once,
   * and it can occupy too much memory. The estimate is ~600Kb for 1000 commits.</p>
   */
  @NotNull
  public static List<GitCommit> history(@NotNull Project project, @NotNull VirtualFile root, String... parameters)
    throws VcsException {
    final VcsLogObjectsFactory factory = GitLogUtil.getObjectsFactoryWithDisposeCheck(project);
    if (factory == null) {
      return Collections.emptyList();
    }
    return GitLogUtil.collectFullDetails(project, root, parameters);
  }

  @NotNull
  public static String[] formHashParameters(@NotNull GitVcs vcs, @NotNull Collection<String> hashes) {
    List<String> parameters = ContainerUtil.newArrayList();

    parameters.add(GitLogUtil.getNoWalkParameter(vcs));
    parameters.addAll(hashes);

    return ArrayUtil.toStringArray(parameters);
  }

  public static void loadDetails(@NotNull Project project,
                                 @NotNull VirtualFile root,
                                 @NotNull Consumer<? super GitCommit> commitConsumer,
                                 @NotNull String... parameters) throws VcsException {
    GitLogUtil.readFullDetails(project, root, commitConsumer, parameters);
  }

  public static long getAuthorTime(@NotNull Project project, @NotNull FilePath path, @NotNull String commitsId) throws VcsException {
    // adjust path using change manager
    path = getLastCommitName(project, path);
    final VirtualFile root = GitUtil.getGitRoot(path);
    GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.SHOW);
    GitLogParser parser = new GitLogParser(project, GitLogParser.NameStatus.STATUS, AUTHOR_TIME);
    h.setSilent(true);
    h.addParameters("--name-status", parser.getPretty(), "--encoding=UTF-8");
    h.addParameters(commitsId);

    String output = h.run();
    GitLogRecord logRecord = parser.parseOneRecord(output);
    if (logRecord == null) throw new VcsException("Can not parse log output \"" + output + "\"");
    return logRecord.getAuthorTimeStamp();
  }

  /**
   * Get name of the file in the last commit. If file was renamed, returns the previous name.
   *
   * @param project the context project
   * @param path    the path to check
   * @return the name of file in the last commit or argument
   */
  public static FilePath getLastCommitName(@NotNull Project project, FilePath path) {
    if (project.isDefault()) return path;
    final ChangeListManager changeManager = ChangeListManager.getInstance(project);
    final Change change = changeManager.getChange(path);
    if (change != null && change.getType() == Change.Type.MOVED) {
      // GitContentRevision r = (GitContentRevision)change.getBeforeRevision();
      assert change.getBeforeRevision() != null : "Move change always have beforeRevision";
      path = change.getBeforeRevision().getFile();
    }
    return path;
  }

  @Nullable
  public static GitRevisionNumber getMergeBase(@NotNull Project project, @NotNull VirtualFile root, @NotNull String first,
                                               @NotNull String second)
    throws VcsException {
    GitSimpleHandler h = new GitSimpleHandler(project, root, GitCommand.MERGE_BASE);
    h.setSilent(true);
    h.addParameters(first, second);
    String output = h.run().trim();
    if (output.length() == 0) {
      return null;
    }
    else {
      return GitRevisionNumber.resolve(project, root, output);
    }
  }
}
