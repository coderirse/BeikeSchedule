# Course hiding and exam refresh Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow hiding one class occurrence, clarify week labels and recovery entries, and refresh exams independently.

**Architecture:** Keep course week bitmaps intact and store date exceptions on each row. Filter before merging rows for the grid, reminders and next-class selection. Reuse the login screen with an explicit exams-only synchronization scope and a dedicated script.

**Tech Stack:** Kotlin, Jetpack Compose, Room, DataStore, kotlinx.serialization, JavaScript, JUnit.

---

### Task 1: Occurrence storage and visibility

- Modify `app/src/main/java/com/caeamer/beikeschedule/data/local/{CourseEntity,CourseDao,AppDatabase}.kt`: add `hiddenDates` with empty default and migration 6→7.
- Create `app/src/main/java/com/caeamer/beikeschedule/model/CourseVisibility.kt`: date encoding, filtering, preservation and recovery grouping.
- Modify `model/CourseMerger.kt` to expose the underlying row groups for a rendered card.
- Modify `data/repo/ScheduleRepository.kt` for atomic occurrence updates and preservation across imports; extend `data/backup/CloudSnapshot.kt` for compatible serialization.
- Test date isolation, row grouping, restoration and import preservation in `app/src/test/java/com/caeamer/beikeschedule/CourseVisibilityTest.kt`.

### Task 2: Course UI and reminders

- Modify `ui/schedule/{ScheduleScreen,CourseDetailSheet,SemesterSettingsPage,ScheduleViewModel}.kt` to carry the clicked date, offer hide scope, and display complete recovery entries.
- Modify `model/{NextClass,ReminderCourses}.kt` and `reminder/ClassReminderScheduler.kt` to filter the raw rows by date before merging.
- Modify `model/WeekUtils.kt` and `WeekUtilsTest.kt`: contiguous alternating sequences only, explicit single-week card label.
- Test reminders and next-class behavior in existing suites; no tests that simply mirror Compose layout.

### Task 3: Exam-only refresh

- Modify `ui/sync/{SyncPlan,UnifiedSyncViewModel,UnifiedSyncScreen}.kt` to accept a scope; exams-only runs identity then exams and scopes retries identically.
- Create `app/src/main/assets/import/jw_exams.js` and `import/ExamsBridge.kt`: fetch current semester and exams only; report errors instead of returning a false empty result.
- Modify `MainActivity.kt`, `ui/grades/{GradesScreen,GradesViewModel}.kt`, `data/pref/SettingsStore.kt`, `data/backup/CloudSnapshot.kt` to route exams refresh and show its independent timestamp.
- Add scope/retry tests in `ui/sync/SyncPlanTest.kt` and execute the exam script under a mocked fetch runtime to verify request and error behavior.

### Task 4: Verification and documentation

- Run `./gradlew.bat testDebugUnitTest assembleDebug --console=plain`, expecting BUILD SUCCESSFUL.
- Run `./gradlew.bat lintDebug minifyReleaseWithR8 --console=plain`, expecting BUILD SUCCESSFUL with no lint errors.
- Verify the generated Room v7 schema against the migration and check UI on a device if available.
- Update `README.md` behavior descriptions; inspect `git diff --check` and the final scoped diff. Leave changes reviewable on `codex/course-hide-and-exam-refresh`.

## Validation results (2026-11-08)

- `testDebugUnitTest`: 426 tests across 47 suites, zero failures/errors. Includes date/session isolation, makeup days, import splits and different classrooms, recovery grouping, reminders, next-class selection, labels, snapshot compatibility, and scoped exam retries.
- `node --test app/src/test/js/jw_exams.test.cjs`: 9/9 passed. Validates exact requested endpoints/form parameters, duplicate-injection guard, real empty results, expired login, malformed responses and network/HTTP failures. Added to CI.
- `assembleDebug`, `lintDebug`, `minifyReleaseWithR8`: passed. Lint reports zero errors (45 warnings and 7 hints).
- SQLite migration 6→7: executed the migration SQL against the exported v6 schema with an existing hidden course; all migrated table schemas match v7 and existing course/name/note/hidden state remain intact.
- Pixel_10_Pro emulator: verified single hide removes only the selected date/session, single and semester recovery, complete two-line long course name, and both exam refresh entries opening the dedicated exam login screen. Test hides restored.
- Debug artifact: `app/build/outputs/apk/debug/app-debug.apk` (independent debug application ID).
- Remaining external validation: real university login and exam retrieval require the user's authenticated session; script behavior was tested with mocked responses.
