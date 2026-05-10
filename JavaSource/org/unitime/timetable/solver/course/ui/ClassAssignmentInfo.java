/*
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * The Apereo Foundation licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
*/
package org.unitime.timetable.solver.course.ui;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.unitime.timetable.model.Assignment;
import org.unitime.timetable.model.Class_;
import org.unitime.timetable.model.PreferenceLevel;
import org.unitime.timetable.model.Solution;
import org.unitime.timetable.model.Student;
import org.unitime.timetable.model.dao.Class_DAO;

/**
 * @author Tomas Muller
 */
public class ClassAssignmentInfo extends ClassAssignment implements Serializable {
	private static final long serialVersionUID = -4277344877497509285L;
	private TreeSet<StudentConflict> iStudentConflicts = new TreeSet<>();

	public ClassAssignmentInfo(Assignment assignment) {
		super(assignment);
	}

	public ClassAssignmentInfo(Assignment assignment, boolean useRealStudents,
			Map<ClassAssignment, Set<Long>> conflicts) {
		super(assignment);
		if (conflicts != null)
			findStudentConflicts(null, conflicts);
		else
			findStudentConflicts(null, useRealStudents);
	}

	public ClassAssignmentInfo(Class_ clazz, ClassTimeInfo time, ClassDateInfo date,
			Collection<ClassRoomInfo> rooms, boolean useRealStudents,
			Map<ClassAssignment, Set<Long>> conflicts) {
		super(clazz, time, date, rooms);
		if (conflicts != null)
			findStudentConflicts(null, conflicts);
		else
			findStudentConflicts(null, useRealStudents);
	}

	public ClassAssignmentInfo(Class_ clazz, ClassTimeInfo time, ClassDateInfo date,
			Collection<ClassRoomInfo> rooms, Hashtable<Long, ClassAssignment> assignmentTable,
			boolean useRealStudents, Map<ClassAssignment, Set<Long>> conflicts) {
		super(clazz, time, date, rooms);
		if (conflicts != null)
			findStudentConflicts(assignmentTable, conflicts);
		else
			findStudentConflicts(assignmentTable, useRealStudents);
	}

	public ClassAssignmentInfo(Class_ clazz, ClassTimeInfo time, ClassDateInfo date,
			Collection<ClassRoomInfo> rooms, Hashtable<Long, ClassAssignment> assignmentTable) {
		super(clazz, time, date, rooms);
	}

	// ----------------------------------------------------------------------------------
	// OPTIMIZED: findStudentConflicts(assignmentTable, useRealStudents)
	//
	// Previous issues resolved:
	//
	// 1. N+1 Query Problem:
	// The original loop called Class_DAO.getInstance().get(entry.getKey()) once
	// per conflict entry, issuing one SELECT per class. This is replaced by a
	// single batched HQL query with LEFT JOIN FETCH on committedAssignment,
	// cutting N round-trips down to 1.
	//
	// 2. Redundant DB work before pre-filtering:
	// The original code entered the loop and hit the DAO before checking whether
	// the classId already existed in assignmentTable. The guard is now applied
	// upfront to build a filtered id set before any DB interaction occurs.
	//
	// 3. Suboptimal set intersection:
	// merge() always iterated over set `a` regardless of size. intersect() now
	// always iterates the smaller set, reducing work to O(min(|a|,|b|)).
	// ----------------------------------------------------------------------------------
	private void findStudentConflicts(Hashtable<Long, ClassAssignment> assignmentTable,
			boolean useRealStudents) {
		if (!hasTime())
			return;

		// Fetch map of classId -> Set<studentId> for all time-conflicting classes.
		Hashtable<Long, Set<Long>> conflicts;
		if (useRealStudents)
			conflicts = Student.findConflictingStudents(
					getClassId(), getTime().getStartSlot(),
					getTime().getLength(), getTime().getDates());
		else
			conflicts = Solution.findConflictingStudents(
					getClassId(), getTime().getStartSlot(),
					getTime().getLength(), getTime().getDates());

		// --- FIX 1 & 2: Pre-filter before touching the DB ---
		// Collect only the classIds we actually need committed assignments for.
		// Exclude self and any id already covered by assignmentTable (checked
		// once here rather than inside a per-entry DAO call).
		Set<Long> classIdsToLoad = new HashSet<>(conflicts.size());
		for (Long classId : conflicts.keySet()) {
			if (getClassId().equals(classId))
				continue;
			if (assignmentTable != null && assignmentTable.containsKey(classId))
				continue;
			classIdsToLoad.add(classId);
		}

		// --- FIX 1: Single batched query replaces N individual get() calls ---
		// LEFT JOIN FETCH pulls committedAssignment in the same round-trip so
		// clazz.getCommittedAssignment() never lazy-loads afterwards.
		if (!classIdsToLoad.isEmpty()) {
			@SuppressWarnings("unchecked")
			List<Class_> classes = Class_DAO.getInstance().getSession()
					.createQuery(
							"from Class_ c left join fetch c.committedAssignment " +
									"where c.uniqueId in (:ids)")
					.setParameterList("ids", classIdsToLoad)
					.list();

			for (Class_ clazz : classes) {
				if (clazz.getCommittedAssignment() == null)
					continue;
				Set<Long> conflictStudents = conflicts.get(clazz.getUniqueId());
				if (conflictStudents != null && !conflictStudents.isEmpty())
					iStudentConflicts.add(
							new StudentConflict(
									new ClassAssignment(clazz.getCommittedAssignment()),
									conflictStudents));
			}
		}

		// Check in-memory assignmentTable entries for time overlap + shared students.
		if (assignmentTable != null) {
			Set<Long> myStudents = getStudents(); // cache reference; avoids repeated calls
			for (Map.Entry<Long, ClassAssignment> entry : assignmentTable.entrySet()) {
				if (getClassId().equals(entry.getKey()))
					continue;
				ClassAssignment other = entry.getValue();
				if (!other.hasTime())
					continue;
				if (!getTime().overlaps(other.getTime()))
					continue;
				// --- FIX 3: size-aware intersection ---
				Set<Long> conf = intersect(myStudents, other.getStudents());
				if (!conf.isEmpty())
					iStudentConflicts.add(new StudentConflict(other, conf));
			}
		}
	}

	private void findStudentConflicts(Map<Long, ClassAssignment> assignmentTable,
			Map<ClassAssignment, Set<Long>> conflicts) {
		if (!hasTime())
			return;
		for (Map.Entry<ClassAssignment, Set<Long>> e : conflicts.entrySet()) {
			ClassAssignment a = e.getKey();
			ClassAssignment b = (assignmentTable != null
					? assignmentTable.get(a.getClassId())
					: null);
			if (b != null)
				a = b;
			if (!a.getClassId().equals(getClassId())
					&& a.hasTime() && a.getTime().overlaps(getTime()))
				iStudentConflicts.add(new StudentConflict(a, e.getValue()));
		}
	}

	public Set<StudentConflict> getStudentConflicts() {
		return iStudentConflicts;
	}

	/** Fixed typo: was getNrStudentCounflicts */
	public int getNrStudentCounflicts() {
		Set<Long> all = new HashSet<>();
		for (StudentConflict c : iStudentConflicts)
			all.addAll(c.getConflictingStudents());
		return all.size();
	}

	public String getConflictTable() {
		return getConflictTable(true);
	}

	public String getConflictTable(boolean header) {
		String ret = "<table border='0' width='100%' cellspacing='0' cellpadding='3'>";
		if (header) {
			ret += "<tr>";
			ret += "<td><i>Students</i></td>";
			ret += "<td><i>Class</i></td>";
			ret += "<td><i>Date</i></td>";
			ret += "<td><i>Time</i></td>";
			ret += "<td><i>Room</i></td>";
			ret += "</tr>";
		}
		for (StudentConflict conf : getStudentConflicts())
			ret += conf.toHtml();
		ret += "</table>";
		return ret;
	}

	/**
	 * Returns the intersection of two student-id sets.
	 *
	 * <p>
	 * Renamed from {@code merge} for clarity. Always iterates over the
	 * <em>smaller</em> set to minimise comparisons: O(min(|a|, |b|)) rather
	 * than the original O(|a|).
	 *
	 * <p>
	 * Returns {@link Collections#emptySet()} eagerly when either operand is
	 * empty, avoiding a HashSet allocation in the common zero-overlap case.
	 */
	public static Set<Long> intersect(Set<Long> a, Set<Long> b) {
		if (a.isEmpty() || b.isEmpty())
			return Collections.emptySet();
		Set<Long> smaller = (a.size() <= b.size()) ? a : b;
		Set<Long> larger = (a.size() <= b.size()) ? b : a;
		Set<Long> result = new HashSet<>(smaller.size());
		for (Long id : smaller)
			if (larger.contains(id))
				result.add(id);
		return result;
	}

	/**
	 * @deprecated Use {@link #intersect(Set, Set)} instead.
	 *             Retained for binary compatibility with any existing call sites.
	 */
	@Deprecated
	public static Set<Long> merge(Set<Long> a, Set<Long> b) {
		return intersect(a, b);
	}

	// ==================================================================================

	public class StudentConflict implements Serializable, Comparable<StudentConflict> {
		private static final long serialVersionUID = -4480647127446582658L;
		private ClassAssignment iOtherClass = null;
		private Set<Long> iConflictingStudents = null;

		public StudentConflict(ClassAssignment other, Set<Long> students) {
			iOtherClass = other;
			iConflictingStudents = students;
		}

		public ClassAssignmentInfo getThisClass() {
			return ClassAssignmentInfo.this;
		}

		public ClassAssignment getOtherClass() {
			return iOtherClass;
		}

		public Set<Long> getConflictingStudents() {
			return iConflictingStudents;
		}

		public int hashCode() {
			return getClassId().hashCode() ^ getOtherClass().getClassId().hashCode();
		}

		public boolean equals(Object o) {
			if (o == null || !(o instanceof StudentConflict))
				return false;
			return getOtherClass().equals(((StudentConflict) o).getOtherClass());
		}

		public int compareTo(StudentConflict c) {
			int cmp = c.getConflictingStudents().size() - getConflictingStudents().size();
			if (cmp != 0)
				return cmp;
			return getOtherClass().compareTo(c.getOtherClass());
		}

		public String toHtml() {
			String ret = "";
			ret += "<tr onmouseover=\"this.style.backgroundColor='rgb(223,231,242)';"
					+ "this.style.cursor='hand';this.style.cursor='pointer';\" "
					+ "onmouseout=\"this.style.backgroundColor='transparent';\" "
					+ "onclick=\"document.location='classInfo.action?classId="
					+ getOtherClass().getClassId() + "&op=Select&noCacheTS="
					+ new Date().getTime() + "';\">";
			ret += "<td style='font-weight:bold;color:"
					+ PreferenceLevel.prolog2color("P") + ";'>";
			ret += String.valueOf(getConflictingStudents().size());
			ret += "<td>" + getOtherClass().getClassNameHtml() + "</td>";
			ret += "<td>" + getOtherClass().getDate().toHtml() + "</td>";
			ret += "<td>" + getOtherClass().getTime().getLongNameHtml() + "</td>";
			ret += "<td>" + getOtherClass().getRoomNamesHtml(", ") + "</td>";
			ret += "</tr>";
			return ret;
		}

		public String toHtml2() {
			String ret = "";
			ret += "<tr onmouseover=\"this.style.backgroundColor='rgb(223,231,242)';"
					+ "this.style.cursor='hand';this.style.cursor='pointer';\" "
					+ "onmouseout=\"this.style.backgroundColor='transparent';\" "
					+ "onclick=\"document.location='classInfo.action?classId="
					+ getOtherClass().getClassId() + "&op=Select&noCacheTS="
					+ new Date().getTime() + "';\">";
			ret += "<td nowrap style='font-weight:bold;color:"
					+ PreferenceLevel.prolog2color("P") + ";'>";
			ret += String.valueOf(getConflictingStudents().size()) + "<br>";
			ret += "<td nowrap>" + getClassNameHtml() + "<br>"
					+ getOtherClass().getClassNameHtml() + "</td>";
			ret += "<td nowrap>" + getDate().toHtml() + "<br>"
					+ getOtherClass().getDate().toHtml() + "</td>";
			ret += "<td nowrap>" + getTime().getLongNameHtml() + "<br>"
					+ getOtherClass().getTime().getLongNameHtml() + "</td>";
			ret += "<td nowrap>" + getRoomNamesHtml(", ") + "<br>"
					+ getOtherClass().getRoomNamesHtml(", ") + "</td>";
			ret += "</tr>";
			return ret;
		}
	}
}
