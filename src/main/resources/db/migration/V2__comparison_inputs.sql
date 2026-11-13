CREATE TABLE comparison_input (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES app_user(id),previous_id uuid NOT NULL REFERENCES document_check(id),current_id uuid NOT NULL REFERENCES document_check(id),
 label varchar(150) NOT NULL,before_value varchar(1000) NOT NULL,after_value varchar(1000) NOT NULL,created_at timestamptz,updated_at timestamptz,
 UNIQUE(previous_id,current_id,label)
);
