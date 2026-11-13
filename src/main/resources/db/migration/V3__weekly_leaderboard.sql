-- The Lux each opted-in player earned in each week, from the cases they closed. Weeks start on
-- Monday and count game days, as on the app's Rivals board. Lux follows the app's rules: Easy 10,
-- Medium 25, Hard 50; the daily case 1.5 times that with a half rounded up (Medium daily: 38); the
-- player's own challenges nothing. Drops don't take Lux off here: the board counts what was earned.
create view weekly_leaderboard as
select p.user_id,
       p.name,
       p.sigil_seed,
       date_trunc('week', c.closed_on)::date as week_start,
       sum(
           case
               when c.custom_challenge_id is not null then 0
               when c.is_daily then (base.lux * 3 + 1) / 2
               else base.lux
           end
       )::integer                           as lux
from cases c
         join profiles p on p.user_id = c.user_id
         left join challenges ch on ch.id = c.challenge_id
         cross join lateral (
    select case ch.difficulty when 'EASY' then 10 when 'MEDIUM' then 25 when 'HARD' then 50 else 0 end as lux
    ) base
where c.status = 'CLOSED'
  and p.on_leaderboard
group by p.user_id, p.name, p.sigil_seed, date_trunc('week', c.closed_on);
