import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";

import { fetchMyProfile, updateMyProfile, updateMyStaffProfile } from "../api/auth";
import { isStudentRole } from "../api/roles";
import { ApiError } from "../api/client";
import type { Role, UserProfile } from "../api/types";
import AppHeader from "../components/AppHeader";
import { Icon, PageHero, Spinner } from "../components/ui";

const ROLE_NAME: Record<Role, string> = {
  STUDENT: "Student",
  TNP_COORDINATOR: "Coordinator",
  RECRUITER: "Recruiter",
  TNP_PIC: "Admin",
};

/**
 * Loads the signed-in account, then hands off to whichever form applies.
 *
 * A coordinator sees the exact same academic form a student does - a coordinator is a
 * student, and has no staff fields to edit anyway; the database refuses to store them on
 * anything but a PIC. The PIC gets the staff form, and a recruiter, who has neither marks
 * nor staff details, gets a short note instead of a form.
 */
export default function Profile() {
  const navigate = useNavigate();

  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    fetchMyProfile()
      .then((me) => {
        if (!cancelled) setProfile(me);
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 401) {
          navigate("/login", { replace: true });
          return;
        }
        setError("Could not load your profile");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [navigate]);

  return (
    <>
      <AppHeader profile={profile} active="profile" />

      <main className="mx-auto max-w-5xl px-4 pt-8 pb-16">
        {loading ? (
          <div className="flex items-center gap-3 py-16 text-slate-500">
            <Spinner className="size-5 text-violet-500" />
            Loading...
          </div>
        ) : error || !profile ? (
          <p role="alert" className="alert-error">
            {error ?? "Could not load your profile"}
          </p>
        ) : (
          <>
            <PageHero
              eyebrow={profile.role === "TNP_PIC" ? "Staff details" : profile.role === "RECRUITER" ? "Recruiter account" : "Academic details"}
              title={profile.role === "TNP_PIC" ? "Administration profile" : "Your profile"}
              subtitle={
                profile.role === "TNP_PIC"
                  ? "How students and coordinators see the person in charge of the cell."
                  : profile.role === "RECRUITER"
                    ? "Your account was added by the placement cell to post drives and review applicants."
                    : "These marks decide which drives you are eligible for - keep them accurate."
              }
            />

            <div className="mt-8 grid items-start gap-6 lg:grid-cols-3">
              <IdentityCard profile={profile} />
              <div className="lg:col-span-2">
                {profile.role === "TNP_PIC" ? (
                  <StaffProfileForm profile={profile} />
                ) : profile.role === "RECRUITER" ? (
                  // Nothing to edit: a recruiter has no marks, and the backend refuses the
                  // academic form for them with a 403.
                  <section className="card animate-fade-up p-6 text-sm text-slate-600">
                    <h2 className="text-lg font-bold text-slate-900">Nothing to fill in</h2>
                    <p className="mt-2">
                      Recruiter accounts have no academic details. Post a drive for your company, then review and
                      shortlist the students who apply. To change your email or password, contact the placement cell.
                    </p>
                  </section>
                ) : (
                  <StudentProfileForm profile={profile} />
                )}
              </div>
            </div>
          </>
        )}
      </main>
    </>
  );
}

/** Who is signed in, and - for a student - how much of the profile is filled in. */
function IdentityCard({ profile }: { profile: UserProfile }) {
  const academic = [profile.cgpa, profile.branch, profile.tenthPercentage, profile.twelfthPercentage];
  const filled = academic.filter((value) => value !== null && value !== "").length;
  const percent = Math.round((filled / academic.length) * 100);

  return (
    <aside className="card animate-fade-up overflow-hidden">
      <div className="h-20 bg-linear-to-r from-indigo-500 via-violet-500 to-fuchsia-500" />
      <div className="-mt-10 px-6 pb-6">
        <span
          aria-hidden="true"
          className="grid size-20 place-items-center rounded-3xl border-4 border-white bg-linear-to-br from-sky-400 to-violet-600 text-3xl font-extrabold text-white shadow-lg"
        >
          {profile.email.charAt(0).toUpperCase()}
        </span>
        <p className="mt-3 truncate text-sm font-semibold text-slate-900">{profile.email}</p>
        <p className="text-xs text-slate-500">
          {ROLE_NAME[profile.role]}
        </p>

        {isStudentRole(profile.role) && (
          <div className="mt-5">
            <div className="flex items-center justify-between text-xs font-semibold">
              <span className="text-slate-600">Profile completeness</span>
              <span className={percent === 100 ? "text-emerald-600" : "text-amber-600"}>{percent}%</span>
            </div>
            <div className="mt-2 h-2.5 overflow-hidden rounded-full bg-slate-100">
              <div
                className={`h-full rounded-full bg-linear-to-r transition-all duration-700 ${
                  percent === 100 ? "from-emerald-400 to-teal-500" : "from-amber-400 to-orange-500"
                }`}
                style={{ width: `${percent}%` }}
              />
            </div>
            <p className="mt-2 text-xs text-slate-500">
              {percent === 100
                ? "All set - eligibility checks will use these marks."
                : "Finish this to unlock the drives you qualify for."}
            </p>
          </div>
        )}
      </div>
    </aside>
  );
}

/** Empty strings rather than numbers, because an <input> always holds text. */
interface StudentFormState {
  cgpa: string;
  branch: string;
  tenthPercentage: string;
  twelfthPercentage: string;
  backlogs: string;
}

function StudentProfileForm({ profile }: { profile: UserProfile }) {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<StudentFormState>({
    cgpa: profile.cgpa?.toString() ?? "",
    branch: profile.branch ?? "",
    tenthPercentage: profile.tenthPercentage?.toString() ?? "",
    twelfthPercentage: profile.twelfthPercentage?.toString() ?? "",
    backlogs: profile.backlogs.toString(),
  });

  function update(field: keyof StudentFormState, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSaving(true);

    try {
      // The inputs hold strings; the API expects numbers. Converting here rather than in
      // the api layer keeps that layer a faithful mirror of the backend contract.
      await updateMyProfile({
        cgpa: Number(form.cgpa),
        branch: form.branch,
        tenthPercentage: Number(form.tenthPercentage),
        twelfthPercentage: Number(form.twelfthPercentage),
        backlogs: Number(form.backlogs),
      });
      // `replace` so the back button does not return to a form that was just submitted -
      // the drives board is the reason someone fills this in, so it is the natural next
      // stop rather than a "Saved." message they would have to dismiss themselves.
      navigate("/drives", { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not save your profile");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="card animate-fade-up p-6 [animation-delay:80ms] sm:p-8">
      {!profile.complete && (
        <p className="mb-6 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50/90 px-4 py-3 text-sm text-amber-800">
          <Icon name="warning" className="mt-0.5 size-4" />
          Fill this in to see which drives you are eligible for.
        </p>
      )}

      {error && (
        <p role="alert" className="alert-error animate-pop mb-6">
          {error}
        </p>
      )}

      <div className="grid gap-5 sm:grid-cols-2">
        <Field
          label="CGPA"
          value={form.cgpa}
          onChange={(v) => update("cgpa", v)}
          type="number"
          step="0.01"
          min="0"
          max="10"
          placeholder="8.25"
        />
        <Field
          label="Branch"
          value={form.branch}
          onChange={(v) => update("branch", v)}
          placeholder="CSE"
          hint="Stored in capitals; matching ignores case."
        />
        <Field
          label="10th percentage"
          value={form.tenthPercentage}
          onChange={(v) => update("tenthPercentage", v)}
          type="number"
          step="0.01"
          min="0"
          max="100"
          placeholder="88.5"
        />
        <Field
          label="12th percentage"
          value={form.twelfthPercentage}
          onChange={(v) => update("twelfthPercentage", v)}
          type="number"
          step="0.01"
          min="0"
          max="100"
          placeholder="85"
        />
        <Field
          label="Active backlogs"
          value={form.backlogs}
          onChange={(v) => update("backlogs", v)}
          type="number"
          min="0"
        />
      </div>

      <FormActions navigate={navigate} saving={saving} />
    </form>
  );
}

/** Empty strings for the same reason as the student form - inputs hold text either way. */
interface StaffFormState {
  designation: string;
  department: string;
  staffId: string;
  officeLocation: string;
  phoneNumber: string;
}

/**
 * The person in charge's own details. Every field is optional on the backend - there is
 * no "complete" gate here, unlike the student form, because nothing else in the system
 * depends on these being filled in.
 */
function StaffProfileForm({ profile }: { profile: UserProfile }) {
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<StaffFormState>({
    designation: profile.designation ?? "",
    department: profile.department ?? "",
    staffId: profile.staffId ?? "",
    officeLocation: profile.officeLocation ?? "",
    phoneNumber: profile.phoneNumber ?? "",
  });

  function update(field: keyof StaffFormState, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  /** Blank means "clear this field" - PUT replaces the whole sub-resource. */
  const orNull = (value: string) => (value.trim() === "" ? null : value.trim());

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSaving(true);

    try {
      await updateMyStaffProfile({
        designation: orNull(form.designation),
        department: orNull(form.department),
        staffId: orNull(form.staffId),
        officeLocation: orNull(form.officeLocation),
        phoneNumber: orNull(form.phoneNumber),
      });
      navigate("/drives", { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not save your profile");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="card animate-fade-up p-6 [animation-delay:80ms] sm:p-8">
      {error && (
        <p role="alert" className="alert-error animate-pop mb-6">
          {error}
        </p>
      )}

      <div className="grid gap-5 sm:grid-cols-2">
        <Field
          label="Designation"
          value={form.designation}
          onChange={(v) => update("designation", v)}
          placeholder="Training & Placement Officer"
          required={false}
        />
        <Field
          label="Department"
          value={form.department}
          onChange={(v) => update("department", v)}
          placeholder="Training & Placement Cell"
          required={false}
        />
        <Field
          label="Staff ID"
          value={form.staffId}
          onChange={(v) => update("staffId", v)}
          required={false}
        />
        <Field
          label="Office location"
          value={form.officeLocation}
          onChange={(v) => update("officeLocation", v)}
          placeholder="Admin Block, Room 12"
          required={false}
        />
        <Field
          label="Phone number"
          value={form.phoneNumber}
          onChange={(v) => update("phoneNumber", v)}
          type="tel"
          required={false}
        />
      </div>

      <FormActions navigate={navigate} saving={saving} />
    </form>
  );
}

/** Save + back-to-drives, identical on both forms. */
function FormActions({ navigate, saving }: { navigate: ReturnType<typeof useNavigate>; saving: boolean }) {
  return (
    <div className="mt-8 flex flex-wrap gap-3 border-t border-slate-100 pt-6">
      <button type="submit" disabled={saving} className="btn btn-primary">
        {saving ? <Spinner /> : <Icon name="check" />}
        Save
      </button>
      <button type="button" onClick={() => navigate("/drives")} className="btn btn-secondary">
        Back to drives
      </button>
    </div>
  );
}

/** One labelled input. Extracted only to keep the forms above readable. */
function Field(props: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  step?: string;
  min?: string;
  max?: string;
  placeholder?: string;
  hint?: string;
  required?: boolean;
}) {
  return (
    <label className="label">
      {props.label}
      <input
        type={props.type ?? "text"}
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
        required={props.required ?? true}
        step={props.step}
        min={props.min}
        max={props.max}
        placeholder={props.placeholder}
        className="input"
      />
      {props.hint && <span className="hint">{props.hint}</span>}
    </label>
  );
}
