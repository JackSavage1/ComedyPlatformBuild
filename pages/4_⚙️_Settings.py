# Updated the Bad Slava bulk add section

# Changing the insertion of insert_data to remove 'id'
insert_data = {"name": name, "venue": venue, "day_of_week": day_of_week, "start_time": start_time}
insert_data.pop('id', None)  # Ensure 'id' is removed to prevent NotNullViolation

# Now ensure that all necessary fields are present with fallbacks
if not name:
    raise ValueError("Name is required and cannot be None.")
if not venue:
    raise ValueError("Venue is required and cannot be None.")
if not day_of_week:
    raise ValueError("Day of week is required and cannot be None.")
if not start_time:
    raise ValueError("Start time is required and cannot be None.")

add_mic(insert_data)

# Updated the FireMics bulk add section

# Similar changes made here as well
insert_data = {"name": name, "venue": venue, "day_of_week": day_of_week, "start_time": start_time}
insert_data.pop('id', None)  # Ensure 'id' is removed to prevent NotNullViolation

# Checking all required fields
if not name:
    raise ValueError("Name is required and cannot be None.")
if not venue:
    raise ValueError("Venue is required and cannot be None.")
if not day_of_week:
    raise ValueError("Day of week is required and cannot be None.")
if not start_time:
    raise ValueError("Start time is required and cannot be None.")

add_mic(insert_data)
