import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";

import { fetchMyProfile, updateMyProfile, updateMyStaffProfile } from "../api/auth";
import { ApiError } from "../api/client";
import type { UserProfile } from "../api/types";

/**
 * Loads the signed-in account, then hands off to whichever form applies.
 *
 * Only TNP_PIC gets a different form here - a coordinator sees the exact same academic
 * form a student does, unchanged from before this split existed. That is not an
 * oversight: nobody asked for a coordinator-specific profile, and a coordinator's account
 * has no staff fields to edit anyway - the database refuses to store them on anything
 * but a PIC.
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

  if (loading) {
    return <main className="p-8 text-slate-500">Loading...</main>;
  }

  if (error || !profile) {
    return (
      <main className="mx-auto max-w-xl p-6">
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error ?? "Could not load your profile"}
        </p>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-xl p-6">
      <h1 className="text-2xl font-semibold text-slate-900">
        {profile.role === "TNP_PIC" ? "Administration profile" : "Your profile"}
      </h1>
      <p className="mt-1 text-sm text-slate-500">{profile.email}</p>

      {profile.role === "TNP_PIC" ? (
        <StaffProfileForm profile={profile} />
      ) : (
        <StudentProfileForm profile={profile} />
      )}
    </main>
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
    }
  }

  return (
    <>
      {!profile.complete && (
        <p className="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
          Fill this in to see which drives you are eligible for.
        </p>
      )}

      {error && (
        <p role="alert" className="mt-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      <form onSubmit={handleSubmit} className="mt-6 space-y-4">
        <Field
          label="CGPA"
          value={form.cgpa}
          onChange={(v) => update("cgpa", v)}
          type="number"
          step="0.01"
          min="0"
          max="10"
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
        />
        <Field
          label="12th percentage"
          value={form.twelfthPercentage}
          onChange={(v) => update("twelfthPercentage", v)}
          type="number"
          step="0.01"
          min="0"
          max="100"
        />
        <Field
          label="Active backlogs"
          value={form.backlogs}
          onChange={(v) => update("backlogs", v)}
          type="number"
          min="0"
        />

        <FormActions navigate={navigate} />
      </form>
    </>
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
    }
  }

  return (
    <>
      {error && (
        <p role="alert" className="mt-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      <form onSubmit={handleSubmit} className="mt-6 space-y-4">
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

        <FormActions navigate={navigate} />
      </form>
    </>
  );
}

/** Save + back-to-drives, identical on both forms. */
function FormActions({ navigate }: { navigate: ReturnType<typeof useNavigate> }) {
  return (
    <div className="flex gap-3 pt-2">
      <button
        type="submit"
        className="rounded-lg bg-slate-900 px-4 py-2 font-medium text-white hover:bg-slate-700"
      >
        Save
      </button>
      <button
        type="button"
        onClick={() => navigate("/drives")}
        className="rounded-lg border border-slate-300 px-4 py-2 font-medium text-slate-700 hover:bg-slate-100"
      >
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
    <label className="block text-sm font-medium text-slate-700">
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
        className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-slate-900 outline-none focus:border-slate-900"
      />
      {props.hint && (
        <span className="mt-1 block text-xs font-normal text-slate-500">{props.hint}</span>
      )}
    </label>
  );
}
